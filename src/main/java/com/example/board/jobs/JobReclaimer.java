package com.example.board.jobs;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
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
      if (entry.getTotalDeliveryCount() >= props.maxAttempts()) {
        deadLetter(entry);
        continue;
      }
      // XCLAIM … min-idle — "그동안 idle이었다면" 원자적으로 내 것으로. 전달 횟수가 1 늘어난다.
      List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
          props.stream(), props.group(), RECLAIMER, Duration.ofMillis(props.minIdleMs()), entry.getId());
      for (MapRecord<String, Object, Object> record : claimed) {
        Map<String, String> fields = toStrings(record.getValue());
        log.info("[reclaimer] claimed job={} from={} deliveries={}",
            fields.get("jobId"), entry.getConsumerName(), entry.getTotalDeliveryCount() + 1);
        executor.execute(() -> worker.process(RECLAIMER, record.getId().getValue(), fields));
      }
    }
  }

  // 한도까지 실패 → 실패 보관함(jobs:dead)에 기록하고 원래 스트림에서는 ACK(더는 재시도하지 않음)
  private void deadLetter(PendingMessage entry) {
    String recordId = entry.getIdAsString();
    List<MapRecord<String, Object, Object>> records =
        redis.opsForStream().range(props.stream(), Range.closed(recordId, recordId));
    if (!records.isEmpty()) {
      Map<String, String> fields = toStrings(records.get(0).getValue());
      String jobId = fields.get("jobId");
      String error = store.find(jobId).map(JobView::error).orElse("");
      redis.opsForStream().add(props.deadStream(), Map.of(
          "jobId", jobId,
          "originalId", recordId,
          "deliveries", String.valueOf(entry.getTotalDeliveryCount()),
          "error", String.valueOf(error)));
      store.update(jobId, JobStatus.FAILED, Map.of());
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
