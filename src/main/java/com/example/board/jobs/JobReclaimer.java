package com.example.board.jobs;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 단계 19: 재시도 담당 — ACK되지 않고 min-idle 넘게 방치된 job(실패했거나, 처리 중 작업자가 죽음)을 넘겨받는다.
// Redis 6.2의 XAUTOCLAIM은 Spring Data Redis 3.5에 API가 없어 XPENDING(목록) + XCLAIM(넘겨받기)으로 한다.
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobReclaimer {

  static final String RECLAIMER = "reclaimer";
  static final int RETRY_THREADS = 2;

  // 빈 실행 스레드 수. 빈 스레드가 있을 때만 넘겨받아 "넘겨받는 즉시 실행 시작"을 보장한다.
  // 넘겨받고 줄 서서 기다리면 그동안 idle이 다시 쌓여 같은 레코드를 또 넘겨받는다(중복 실행) —
  // "min-idle > 최대 작업 시간" 규칙은 실행이 곧바로 시작될 때만 성립한다.
  private final Semaphore freeThreads = new Semaphore(RETRY_THREADS);

  private final StringRedisTemplate redis;
  private final JobProperties props;
  private final JobStore store;
  private final JobWorker worker;
  private final Executor executor;

  public JobReclaimer(StringRedisTemplate redis, JobProperties props, JobStore store,
                      JobWorker worker, @Qualifier("jobRetryExecutor") Executor executor) {
    this.redis = redis;
    this.props = props;
    this.store = store;
    this.worker = worker;
    this.executor = executor;
  }

  @Scheduled(fixedDelayString = "${app.jobs.reclaim-interval-ms}",
      initialDelayString = "${app.jobs.reclaim-interval-ms}")
  public void reclaim() {
    // XPENDING jobs:stream job-workers - + 50 — 처리 중(ACK 전) 목록: 누가, 얼마나 오래, 몇 번 전달됐나
    PendingMessages pending = redis.opsForStream()
        .pending(props.stream(), props.group(), Range.unbounded(), 50);
    for (PendingMessage entry : pending) {
      if (entry.getElapsedTimeSinceLastDelivery().toMillis() < props.minIdleMs()) {
        continue;                                   // 아직 처리 중일 수 있다 — 기다린다
      }
      try {
        reclaimOne(entry);
      } catch (RuntimeException e) {
        // 한 항목의 실패가 이번 회차의 나머지 항목까지 막지 않게 한다
        log.warn("[reclaimer] record={} 처리 실패: {}", entry.getIdAsString(), e.toString());
      }
    }
  }

  private void reclaimOne(PendingMessage entry) {
    if (entry.getTotalDeliveryCount() >= props.maxAttempts()) {
      deadLetter(entry);
      return;
    }
    if (!freeThreads.tryAcquire()) {
      return;                                       // 실행 스레드가 모두 바쁘다 — 다음 회차에
    }
    // XCLAIM … min-idle — "그동안 idle이었다면" 원자적으로 내 것으로. 전달 횟수가 1 늘어난다.
    List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
        props.stream(), props.group(), RECLAIMER, Duration.ofMillis(props.minIdleMs()), entry.getId());
    if (claimed.isEmpty()) {
      freeThreads.release();                        // 그새 다른 쪽이 가져갔다
      return;
    }
    MapRecord<String, Object, Object> record = claimed.get(0);
    Map<String, String> fields = toStrings(record.getValue());
    log.info("[reclaimer] claimed job={} from={} deliveries={}",
        fields.get("jobId"), entry.getConsumerName(), entry.getTotalDeliveryCount() + 1);
    try {
      executor.execute(() -> {
        try {
          worker.process(RECLAIMER, record.getId().getValue(), fields);
        } catch (RuntimeException e) {
          log.warn("[reclaimer] job={} 실행 중 오류: {}", fields.get("jobId"), e.toString());
        } finally {
          freeThreads.release();
        }
      });
    } catch (RuntimeException e) {
      freeThreads.release();                        // 실행을 맡기지도 못했다 — PEL에 남아 다음 회차에
      throw e;
    }
  }

  // 한도까지 실패 → 실패 보관함(jobs:dead)에 기록하고 원래 스트림에서는 ACK(더는 재시도하지 않음)
  private void deadLetter(PendingMessage entry) {
    String recordId = entry.getIdAsString();
    List<MapRecord<String, Object, Object>> records =
        redis.opsForStream().range(props.stream(), Range.closed(recordId, recordId));
    if (!records.isEmpty()) {
      Map<String, String> fields = toStrings(records.get(0).getValue());
      String jobId = fields.get("jobId");                 // redis-cli로 넣은 레코드라면 없을 수 있다
      String error = jobId == null ? "필수 필드 누락" : store.find(jobId).map(JobView::error).orElse("");
      Map<String, String> dead = new HashMap<>();
      dead.put("originalId", recordId);
      dead.put("deliveries", String.valueOf(entry.getTotalDeliveryCount()));
      dead.put("error", error == null ? "" : error);
      if (jobId != null) {
        dead.put("jobId", jobId);
        store.update(jobId, JobStatus.FAILED, Map.of());
      }
      redis.opsForStream().add(props.deadStream(), dead);
      log.warn("[reclaimer] dead-letter job={} deliveries={} → {}",
          jobId, entry.getTotalDeliveryCount(), props.deadStream());
    }
    redis.opsForStream().acknowledge(props.stream(), props.group(), recordId);
  }

  private static Map<String, String> toStrings(Map<Object, Object> value) {
    Map<String, String> map = new HashMap<>();
    value.forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
    return map;
  }
}
