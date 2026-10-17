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

// 단계 19: JobReclaimer — "끝나지 않은 채 방치된 job"을 찾아 다시 시키는 재시도 담당.
//
// 한 줄 요약
//   5초마다 Redis의 처리 중 목록(PEL)을 훑어보고, 15초 넘게 완료 도장(ACK)이 없는 job을
//   넘겨받아(XCLAIM) 다시 처리시킨다. 3번이나 실패한 job은 포기하고 실패 보관함(jobs:dead)으로 옮긴다.
//
// 식당 비유
//   매장을 도는 "매니저". 5초마다 주문표 걸이를 둘러보다가,
//   요리사가 들고 갔는데 15초가 넘도록 완료 도장이 없는 주문표를 발견하면
//     - 요리사가 실패했거나 쓰러진 것으로 보고, 그 주문표를 가져와 다른 사람에게 다시 맡긴다.
//     - 이미 3번이나 실패한 주문표라면 "처리 불가" 보관함에 넣고 걸이에서 뗀다.
//
// 왜 이런 담당이 필요한가
//   작업자(JobWorker)는 실패하면 ACK하지 않고 그냥 넘어간다. 작업자는 "새 job"만 받아 오므로
//   실패한 job은 아무도 다시 집어 가지 않는다. 처리 중에 서버가 죽은 job도 마찬가지다.
//   이 클래스가 없으면 그런 job은 영원히 RETRY_WAIT/RUNNING 상태로 멈춰 있게 된다.
//
// 어떤 객체인가
//   @Component → Spring이 1개 만든다. 단, app.jobs.enabled=false(테스트)면 만들지 않는다.
//   스레드가 아니다. reclaim() 메서드를 Spring 스케줄러가 5초마다 대신 불러 준다(@Scheduled).
//
// 관련 스레드 두 종류
//   scheduling-1        : 5초마다 reclaim()을 실행해 "누구를 다시 시킬지" 고르는 스레드(Spring 스케줄러)
//   job-retry-1, -2     : 골라낸 job을 실제로 다시 처리하는 스레드(JobQueueConfig.jobRetryExecutor)
//   고르는 일과 처리하는 일을 나눈 이유: scheduling-1 스레드는 단계 18의 SSE heartbeat와 같이 쓰는데,
//   여기서 10초짜리 job을 직접 처리하면 heartbeat가 밀린다.
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobReclaimer {

  // 넘겨받은 job을 처리할 때 쓰는 작업자 이름(로그와 상태판에 "reclaimer"로 찍힌다)
  static final String RECLAIMER = "reclaimer";

  // 재시도용 스레드 수(job-retry-1, job-retry-2). JobQueueConfig의 스레드 풀 크기도 이 값을 쓴다.
  static final int RETRY_THREADS = 2;

  // "지금 비어 있는 재시도 스레드 수"를 세는 카운터(처음 2).
  //   job을 넘겨받기 전에 하나를 빌리고(tryAcquire), 처리가 끝나면 돌려준다(release).
  //   비어 있는 스레드가 없으면 넘겨받지 않는다.
  // 왜 필요한가: 넘겨받아 놓고 실행 대기열에서 기다리기만 하면, 그동안 "방치 시간"이 다시 15초를 넘어
  //   다음 회차에 같은 job을 또 넘겨받는다 → 같은 일을 두 번 하게 된다(중복 실행).
  //   빈 스레드가 있을 때만 넘겨받으면 "넘겨받자마자 바로 처리 시작"이 보장된다.
  private final Semaphore freeThreads = new Semaphore(RETRY_THREADS);

  private final StringRedisTemplate redis;   // Redis 명령 도구(XPENDING, XCLAIM, XACK 등)
  private final JobProperties props;         // 설정값(15초, 3번 같은 기준)
  private final JobStore store;              // job 상태판(FAILED로 바꿀 때 사용)
  private final JobWorker worker;            // 다시 처리할 때 쓰는 "조리법" — 작업자 스레드와 같은 객체
  private final Executor executor;           // 재시도 전용 스레드 풀(job-retry-1, -2)

  // @Qualifier("jobRetryExecutor"): 스레드 풀이 여러 개 있을 수 있어서, JobQueueConfig가 만든
  //   그 이름의 풀을 정확히 지정해 받는다. 테스트에서는 이 자리에 Runnable::run(즉시 실행)을 넣는다.
  public JobReclaimer(StringRedisTemplate redis, JobProperties props, JobStore store,
                      JobWorker worker, @Qualifier("jobRetryExecutor") Executor executor) {
    this.redis = redis;
    this.props = props;
    this.store = store;
    this.worker = worker;
    this.executor = executor;
  }

  // 5초마다 자동 실행되는 "순찰".
  //   fixedDelay: 이전 순찰이 끝난 뒤 5초 쉬고 다음 순찰 — 순찰끼리 겹치지 않는다.
  //   initialDelay: 앱이 뜨고 5초 뒤 첫 순찰.
  @Scheduled(fixedDelayString = "${app.jobs.reclaim-interval-ms}",
      initialDelayString = "${app.jobs.reclaim-interval-ms}")
  public void reclaim() {
    // 처리 중 목록(PEL)을 최대 50개 가져온다.  (redis-cli: XPENDING jobs:stream job-workers - + 50)
    //   항목마다 "어떤 job을, 누가 들고 갔고, 몇 ms 동안 방치됐고, 몇 번 전달됐는지"가 들어 있다.
    PendingMessages pending = redis.opsForStream()
        .pending(props.stream(), props.group(), Range.unbounded(), 50);
    for (PendingMessage entry : pending) {
      // 방치된 지 15초가 안 됐으면 아직 누가 처리 중일 수 있다 → 건드리지 않는다.
      //   (작업 시간 상한이 10초라서, 15초 넘게 소식이 없으면 실패했거나 죽은 것으로 본다)
      if (entry.getElapsedTimeSinceLastDelivery().toMillis() < props.minIdleMs()) {
        continue;
      }
      try {
        reclaimOne(entry);
      } catch (RuntimeException e) {
        // job 하나에서 문제가 나도 나머지 job 순찰은 계속한다.
        log.warn("[reclaimer] record={} 처리 실패: {}", entry.getIdAsString(), e.toString());
      }
    }
  }

  // 방치된 job 하나를 처리한다: 포기할지(3번 실패) / 다시 시킬지 결정.
  private void reclaimOne(PendingMessage entry) {
    // 이미 3번 전달됐으면(= 3번 시도했으면) 더 시도하지 않고 실패 보관함으로 보낸다.
    if (entry.getTotalDeliveryCount() >= props.maxAttempts()) {
      deadLetter(entry);
      return;
    }

    // 비어 있는 재시도 스레드가 없으면 이번에는 넘어간다(다음 순찰에서 다시 본다).
    if (!freeThreads.tryAcquire()) {
      return;
    }

    // 넘겨받기(XCLAIM): 이 job의 주인을 worker-1 같은 원래 작업자에서 "reclaimer"로 바꾼다.
    //   "15초 넘게 방치된 게 맞을 때만" 바꾼다 — 그사이 누가 처리했다면 아무것도 안 돌려준다.
    //   넘겨받으면 Redis가 "전달 횟수"를 1 올린다 → 이 숫자로 몇 번째 시도인지 센다.
    List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
        props.stream(), props.group(), RECLAIMER, Duration.ofMillis(props.minIdleMs()), entry.getId());
    if (claimed.isEmpty()) {
      freeThreads.release();                        // 그새 다른 쪽이 가져갔다 — 빌린 스레드 반납
      return;
    }

    MapRecord<String, Object, Object> record = claimed.get(0);
    Map<String, String> fields = toStrings(record.getValue());   // 주문표 내용을 문자열 Map으로
    log.info("[reclaimer] claimed job={} from={} deliveries={}",
        fields.get("jobId"), entry.getConsumerName(), entry.getTotalDeliveryCount() + 1);

    // 재시도 스레드(job-retry-N)에게 "이 job을 JobWorker로 다시 처리해"라고 맡긴다.
    //   처리가 끝나면(성공이든 실패든) finally에서 빌린 스레드 자리를 반납한다.
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
      freeThreads.release();                        // 맡기지도 못했다 — job은 PEL에 남아 다음 순찰에서 다시 시도
      throw e;
    }
  }

  // 3번 실패한 job을 포기하는 절차.
  //   1) 원래 주문표 내용을 읽어 실패 보관함(jobs:dead)에 "무엇이, 몇 번, 왜 실패했는지" 기록
  //   2) 상태판을 FAILED로 변경
  //   3) 원래 큐에서 ACK — 처리 중 목록에서 빠지므로 더는 재시도되지 않는다
  private void deadLetter(PendingMessage entry) {
    String recordId = entry.getIdAsString();
    // PEL에는 job 내용이 없어서, 큐에서 그 번호의 레코드를 직접 읽는다(XRANGE id id).
    List<MapRecord<String, Object, Object>> records =
        redis.opsForStream().range(props.stream(), Range.closed(recordId, recordId));
    if (!records.isEmpty()) {
      Map<String, String> fields = toStrings(records.get(0).getValue());
      String jobId = fields.get("jobId");                 // redis-cli로 넣은 엉터리 레코드라면 없을 수 있다
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

  // XCLAIM/XRANGE는 내용을 Map<Object, Object>로 돌려준다 → JobWorker가 받는 Map<String, String>으로 바꾼다.
  private static Map<String, String> toStrings(Map<Object, Object> value) {
    Map<String, String> map = new HashMap<>();
    value.forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
    return map;
  }
}
