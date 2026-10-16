---
step: 19
track: domain
tags: [jobs, queue, redis-streams, answers]
requires: ["[[JOB-QUEUE-WORKBOOK]]"]
status: 완료
---

# 단계 19 정답 — 작업 큐(Redis Streams)

> [[JOB-QUEUE-WORKBOOK]]의 Step별 정답이다. **먼저 워크북의 "직접 작성"을 해 본 뒤** 막힐 때만 펼쳐 본다.
> 모든 코드는 정답 브랜치 `answer/job-queue`의 최종 커밋(`f915a7b`)에서 그대로 생성했다(손으로 옮기지 않음).
> 정답 브랜치는 리뷰·E2E에서 찾은 문제(prefetch, 오류 시 구독 취소, 재시도 중복 실행, 독약 레코드)까지 고친 최종본이다.

---

## 1. 파일 한눈에 보기

| Step | 파일 | 비고 |
|------|------|------|
| 1 | `application.yaml`(main·test), `ErrorCode`, `JobProperties` | |
| 2 | `JobStatus`, `JobView`, `JobStore` | |
| 3 | `JobSubmitRequest`, `JobProducer`, `JobController` | `JobController`의 `/stats`는 Step 7에서 추가 |
| 4 | `JobWorker`, `JobQueueConfig` | `JobQueueConfig`의 `jobRetryExecutor` 빈은 Step 5에서 추가 |
| 5 | `JobReclaimer` (+ `jobRetryExecutor`) | |
| 7 | `JobStats`, `JobMonitor` (+ `/stats`) | |
| 8 | `JobPropertiesBindingTest`, `JobControllerTest`, `JobFlowRedisTest` | |

---

## 2. Step 1 — 설정

`src/main/resources/application.yaml` — `app:` 아래(`upload:` 위)에 추가:

```yaml
  # 단계 19: 작업 큐(Redis Streams) — 테스트 컨텍스트는 enabled=false(리스너·재시도 담당 미생성)
  jobs:
    enabled: ${APP_JOBS_ENABLED:true}
    stream: jobs:stream
    group: job-workers
    dead-stream: jobs:dead
    workers: 2
    max-attempts: 3
    # 마지막 전달 후 이 시간 동안 ACK가 없으면 "버려진 job"으로 보고 넘겨받는다.
    # 반드시 최대 작업 시간(10초)보다 길어야 한다 — 짧으면 실행 중인 job을 가로채 중복 실행된다.
    min-idle-ms: 15000
    reclaim-interval-ms: 5000
    status-ttl-hours: 24
    work-unit-ms: 1000
```

`src/test/resources/application.yaml` — `app:` 아래에 추가:

```yaml
  # 단계 19: 테스트에선 Redis 리스너를 띄우지 않는다(Redis 없이 전체 컨텍스트가 떠야 함)
  jobs:
    enabled: false
    stream: jobs:stream
    group: job-workers
    dead-stream: jobs:dead
    workers: 2
    max-attempts: 3
    min-idle-ms: 15000
    reclaim-interval-ms: 5000
    status-ttl-hours: 24
    work-unit-ms: 1000
```

`ErrorCode` — NOT_FOUND 그룹 끝에 추가:

```java
  // 단계 19: 작업 큐 — 상태 Hash는 TTL(1일) 뒤 사라지므로 오래된 jobId도 404다.
  JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "작업을 찾을 수 없습니다."),
```

`src/main/java/com/example/board/jobs/JobProperties.java`

```java
package com.example.board.jobs;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 단계 19: app.jobs.* 설정 — BoardApplication의 @ConfigurationPropertiesScan이 빈으로 등록한다.
@ConfigurationProperties(prefix = "app.jobs")
public record JobProperties(
    boolean enabled,
    String stream,
    String group,
    String deadStream,
    int workers,
    int maxAttempts,
    long minIdleMs,
    long reclaimIntervalMs,
    long statusTtlHours,
    long workUnitMs
) {
}
```

---

## 3. Step 2 — 상태 저장소

`src/main/java/com/example/board/jobs/JobStatus.java`

```java
package com.example.board.jobs;

// 단계 19: QUEUED → RUNNING → DONE, 실패면 RETRY_WAIT(PEL에서 대기) → … → 한도 초과 시 FAILED
public enum JobStatus {
  QUEUED,
  RUNNING,
  RETRY_WAIT,
  DONE,
  FAILED
}
```

`src/main/java/com/example/board/jobs/JobView.java`

```java
package com.example.board.jobs;

import java.util.Map;

public record JobView(
    String jobId,
    JobStatus status,
    long attempts,
    String worker,
    String result,
    String error,
    String createdAt,
    String updatedAt
) {

  // Redis Hash(HGETALL) → 응답. 값은 모두 문자열로 저장되어 있다.
  static JobView from(String jobId, Map<Object, Object> hash) {
    return new JobView(
        jobId,
        JobStatus.valueOf((String) hash.get("status")),
        Long.parseLong((String) hash.getOrDefault("attempts", "0")),
        (String) hash.get("worker"),
        (String) hash.get("result"),
        (String) hash.get("error"),
        (String) hash.get("createdAt"),
        (String) hash.get("updatedAt"));
  }
}
```

`src/main/java/com/example/board/jobs/JobStore.java`

```java
package com.example.board.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 단계 19: job 상태를 Redis Hash jobs:{jobId}에 둔다(큐와 같은 Redis, TTL로 자동 정리).
@Component
@RequiredArgsConstructor
public class JobStore {

  private final StringRedisTemplate redis;
  private final JobProperties props;

  static String key(String jobId) {
    return "jobs:" + jobId;
  }

  public void create(String jobId) {
    String now = Instant.now().toString();
    Map<String, String> fields = new HashMap<>();
    fields.put("status", JobStatus.QUEUED.name());
    fields.put("attempts", "0");
    fields.put("createdAt", now);
    fields.put("updatedAt", now);
    write(jobId, fields);
  }

  public void update(String jobId, JobStatus status, Map<String, String> extra) {
    Map<String, String> fields = new HashMap<>(extra);
    fields.put("status", status.name());
    fields.put("updatedAt", Instant.now().toString());
    write(jobId, fields);
  }

  // HINCRBY — 원자적 증가. 몇 번째 시도인지 화면에 보여 주는 값이다.
  public long incrementAttempts(String jobId) {
    Long value = redis.opsForHash().increment(key(jobId), "attempts", 1);
    return value == null ? 0 : value;
  }

  public Optional<JobView> find(String jobId) {
    Map<Object, Object> hash = redis.opsForHash().entries(key(jobId));
    return hash.isEmpty() ? Optional.empty() : Optional.of(JobView.from(jobId, hash));
  }

  private void write(String jobId, Map<String, String> fields) {
    redis.opsForHash().putAll(key(jobId), fields);
    redis.expire(key(jobId), Duration.ofHours(props.statusTtlHours()));
  }
}
```

---

## 4. Step 3 — 생산자와 API

`src/main/java/com/example/board/jobs/JobSubmitRequest.java`

```java
package com.example.board.jobs;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// durationSeconds 상한 10초는 min-idle(15초)보다 짧아야 한다 — 길면 실행 중인 job을 재시도 담당이 가로챈다.
public record JobSubmitRequest(
    @NotNull @Min(1) @Max(10) Integer durationSeconds,
    @NotNull @Min(0) @Max(5) Integer failTimes
) {
}
```

`src/main/java/com/example/board/jobs/JobProducer.java`

```java
package com.example.board.jobs;

import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 단계 19: 생산자 — 상태부터 만들고(작업자가 항상 찾을 수 있게) 스트림에 넣은 뒤 바로 돌아간다.
@Slf4j
@Component
@RequiredArgsConstructor
public class JobProducer {

  private final StringRedisTemplate redis;
  private final JobProperties props;
  private final JobStore store;

  public JobView submit(JobSubmitRequest request) {
    String jobId = UUID.randomUUID().toString();
    store.create(jobId);
    // XADD jobs:stream * jobId … durationSeconds … failTimes … — "*"는 Redis가 시간 기반 id를 붙인다는 뜻
    RecordId recordId = redis.opsForStream().add(props.stream(), Map.of(
        "jobId", jobId,
        "durationSeconds", String.valueOf(request.durationSeconds()),
        "failTimes", String.valueOf(request.failTimes())));
    log.info("[producer] job={} queued record={} duration={}s failTimes={}",
        jobId, recordId, request.durationSeconds(), request.failTimes());
    return store.find(jobId).orElseThrow();
  }
}
```

최종 `JobController`(Step 3에서는 `JobMonitor` 필드와 `stats()` 메서드를 빼고 작성):

`src/main/java/com/example/board/jobs/JobController.java`

```java
package com.example.board.jobs;

import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 단계 19: 로그인 필요(SecurityConfig의 anyRequest().authenticated()). 202 = "받았고, 나중에 처리한다".
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

  private final JobProducer producer;
  private final JobStore store;
  private final JobMonitor monitor;

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public JobView submit(@Valid @RequestBody JobSubmitRequest request) {
    return producer.submit(request);
  }

  @GetMapping("/stats")
  public JobStats stats() {
    return monitor.stats();
  }

  @GetMapping("/{jobId}")
  public JobView find(@PathVariable String jobId) {
    return store.find(jobId).orElseThrow(() -> new NotFoundException(ErrorCode.JOB_NOT_FOUND));
  }
}
```

---

## 5. Step 4 — 작업자와 리스너 컨테이너

`src/main/java/com/example/board/jobs/JobWorker.java`

```java
package com.example.board.jobs;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 단계 19: 작업자 — 레코드 하나를 처리한다. 성공해야만 XACK, 실패하면 ACK하지 않아 PEL에 남긴다.
@Slf4j
@Component
@RequiredArgsConstructor
public class JobWorker {

  private final StringRedisTemplate redis;
  private final JobProperties props;
  private final JobStore store;

  public boolean process(String workerName, String recordId, Map<String, String> fields) {
    String jobId = fields.get("jobId");
    Integer duration = parseOrNull(fields.get("durationSeconds"));
    Integer failTimes = parseOrNull(fields.get("failTimes"));
    if (jobId == null || duration == null || failTimes == null) {
      discard(workerName, recordId, fields);
      return false;
    }
    long attempt = store.incrementAttempts(jobId);
    store.update(jobId, JobStatus.RUNNING, Map.of("worker", workerName));
    log.info("[{}] start job={} attempt={} record={}", workerName, jobId, attempt, recordId);
    try {
      work(duration);
      if (attempt <= failTimes) {
        throw new IllegalStateException("의도된 실패 " + attempt + "/" + failTimes);
      }
      store.update(jobId, JobStatus.DONE, Map.of(
          "result", workerName + "가 " + duration + "초 작업 완료", "error", ""));
      redis.opsForStream().acknowledge(props.stream(), props.group(), recordId);
      log.info("[{}] done job={} → XACK", workerName, jobId);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("[{}] interrupted job={} — ACK 안 함(PEL에 남김)", workerName, jobId);
      return false;
    } catch (RuntimeException e) {
      store.update(jobId, JobStatus.RETRY_WAIT, Map.of("error", String.valueOf(e.getMessage())));
      log.warn("[{}] fail job={} attempt={} cause={} — ACK 안 함(재시도 대기)",
          workerName, jobId, attempt, e.getMessage());
      return false;
    }
  }

  // 형식이 잘못된 레코드(예: redis-cli로 직접 넣은 것)는 몇 번을 다시 해도 실패한다("독약" 메시지).
  // 재시도로 시간을 버리지 않고 바로 실패 보관함에 남긴 뒤 ACK한다 — 예외를 밖으로 던지면 구독이 멈출 수 있다.
  private void discard(String workerName, String recordId, Map<String, String> fields) {
    redis.opsForStream().add(props.deadStream(), Map.of(
        "originalId", recordId,
        "error", "필수 필드 누락",
        "fields", fields.toString()));
    redis.opsForStream().acknowledge(props.stream(), props.group(), recordId);
    log.warn("[{}] malformed record={} fields={} → {} (ACK)", workerName, recordId, fields, props.deadStream());
  }

  private static Integer parseOrNull(String value) {
    try {
      return value == null ? null : Integer.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // "일하는 척" — 초 단위로 잠든다(테스트는 work-unit-ms를 10ms로 줄인다).
  private void work(int units) throws InterruptedException {
    for (int i = 0; i < units; i++) {
      Thread.sleep(props.workUnitMs());
    }
  }
}
```

최종 `JobQueueConfig`(Step 4에서는 `jobRetryExecutor()` 메서드를 빼고 작성 — `JobReclaimer.RETRY_THREADS`가 Step 5에서 생긴다):

`src/main/java/com/example/board/jobs/JobQueueConfig.java`

```java
package com.example.board.jobs;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// 단계 19: 그룹 생성 + 작업자 N개 구독. app.jobs.enabled=false(테스트)면 이 설정 전체가 빠진다.
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobQueueConfig {

  @Bean(destroyMethod = "stop")
  public StreamMessageListenerContainer<String, MapRecord<String, String, String>> jobListenerContainer(
      RedisConnectionFactory connectionFactory, StringRedisTemplate redis,
      JobProperties props, JobWorker worker) {
    createGroupIfAbsent(redis, props);
    StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
        createContainer(connectionFactory, props, worker);
    container.start();
    log.info("[jobs] 리스너 시작: stream={} group={} workers={}",
        props.stream(), props.group(), props.workers());
    return container;
  }

  // 테스트가 같은 구성을 쓸 수 있게 분리(start는 호출하는 쪽에서).
  static StreamMessageListenerContainer<String, MapRecord<String, String, String>> createContainer(
      RedisConnectionFactory connectionFactory, JobProperties props, JobWorker worker) {
    StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
        StreamMessageListenerContainerOptions.builder()
            .pollTimeout(Duration.ofSeconds(2))      // XREADGROUP BLOCK 2000 — 2초씩 기다리며 반복
            // XREADGROUP … COUNT 1 — 한 번에 하나만 가져간다. 지정하지 않으면 쌓인 job을 한 작업자가
            // 몽땅 가져가(prefetch) 자기 처리 중 목록에 넣고, 다른 작업자는 논다(E2E에서 실측).
            .batchSize(1)
            .errorHandler(JobQueueConfig::backoffOnError)
            .build();
    StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
        StreamMessageListenerContainer.create(connectionFactory, options);
    for (int i = 1; i <= props.workers(); i++) {
      String name = "worker-" + i;
      // autoAcknowledge(false): ACK는 작업자가 성공했을 때 직접 한다.
      // ReadOffset.lastConsumed() = ">" — 아직 누구도 받지 않은 새 job만 읽는다.
      // cancelOnError(e -> false): 기본값(true)이면 읽기 오류 한 번(Redis 단절, 실습 중 DEL로 인한 NOGROUP)에
      //   이 작업자의 구독이 영구 취소된다 — 앱은 멀쩡해 보이는데 job이 영원히 QUEUED로 남는다.
      StreamReadRequest<String> request = StreamReadRequest
          .builder(StreamOffset.create(props.stream(), ReadOffset.lastConsumed()))
          .consumer(Consumer.from(props.group(), name))
          .autoAcknowledge(false)
          .cancelOnError(e -> false)
          .build();
      container.register(request,
          record -> worker.process(name, record.getId().getValue(), record.getValue()));
    }
    return container;
  }

  // 읽기 오류가 이어지면(예: 그룹이 지워진 동안) 폴링 루프가 쉬지 않고 재시도해 로그가 초당 수백 줄 쌓인다.
  // 오류 처리기는 폴링 스레드에서 실행되므로 여기서 1초 쉬면 그 자체가 재시도 간격(backoff)이 된다.
  private static void backoffOnError(Throwable e) {
    log.warn("[jobs] 리스너 오류(1초 후 재시도): {}", e.toString());
    try {
      Thread.sleep(1000);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  // 재시도 담당이 넘겨받은 job을 실행할 전용 스레드(스케줄러 스레드를 막지 않게).
  @Bean
  public ThreadPoolTaskExecutor jobRetryExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(JobReclaimer.RETRY_THREADS);
    executor.setMaxPoolSize(JobReclaimer.RETRY_THREADS);
    // 재시도 담당이 빈 스레드 수만큼만 넘겨주므로 대기열은 거의 쓰이지 않는다(스레드 반납 직전의 짧은 틈만 흡수).
    executor.setQueueCapacity(JobReclaimer.RETRY_THREADS);
    executor.setThreadNamePrefix("job-retry-");
    executor.initialize();
    return executor;
  }

  // XGROUP CREATE jobs:stream job-workers 0 MKSTREAM — 이미 있으면 BUSYGROUP 오류(재기동마다 정상)
  static void createGroupIfAbsent(StringRedisTemplate redis, JobProperties props) {
    try {
      redis.opsForStream().createGroup(props.stream(), ReadOffset.from("0"), props.group());
      log.info("[jobs] 그룹 생성: {}", props.group());
    } catch (DataAccessException e) {
      String message = String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
      if (!message.contains("BUSYGROUP")) {
        throw e;
      }
      log.info("[jobs] 그룹 이미 있음: {}", props.group());
    }
  }
}
```

---

## 6. Step 5 — 재시도 담당과 실패 보관함

`src/main/java/com/example/board/jobs/JobReclaimer.java`

```java
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
```

`JobQueueConfig`에 이때 추가하는 `jobRetryExecutor()`는 Step 4의 최종 파일에 포함되어 있다.

---

## 7. Step 7 — 통계

`src/main/java/com/example/board/jobs/JobStats.java`

```java
package com.example.board.jobs;

import java.util.List;

public record JobStats(long streamLength, long pending, List<ConsumerStat> consumers, long deadLetters) {

  public record ConsumerStat(String name, long pending, long idleMs) {
  }
}
```

`src/main/java/com/example/board/jobs/JobMonitor.java`

```java
package com.example.board.jobs;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 단계 19: 큐 상태를 한눈에 — redis-cli의 XLEN / XPENDING / XINFO CONSUMERS를 API로 옮긴 것.
@Component
@RequiredArgsConstructor
public class JobMonitor {

  private final StringRedisTemplate redis;
  private final JobProperties props;

  public JobStats stats() {
    long length = sizeOf(props.stream());
    long dead = sizeOf(props.deadStream());
    try {
      PendingMessagesSummary summary = redis.opsForStream().pending(props.stream(), props.group());
      List<JobStats.ConsumerStat> consumers = redis.opsForStream()
          .consumers(props.stream(), props.group()).stream()
          .map(c -> new JobStats.ConsumerStat(c.consumerName(), c.pendingCount(), c.idleTimeMs()))
          .toList();
      return new JobStats(length, summary.getTotalPendingMessages(), consumers, dead);
    } catch (DataAccessException e) {
      // 스트림·그룹이 아직 없으면(NOGROUP) 0으로 본다
      return new JobStats(length, 0, List.of(), dead);
    }
  }

  private long sizeOf(String key) {
    Long size = redis.opsForStream().size(key);
    return size == null ? 0 : size;
  }
}
```

`JobController`의 `stats()`는 Step 3의 최종 파일에 포함되어 있다.

---

## 8. Step 8 — 테스트

`src/test/java/com/example/board/jobs/JobPropertiesBindingTest.java`

```java
package com.example.board.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class JobPropertiesBindingTest {

  @Autowired
  JobProperties props;

  @Test
  void should_bindJobSettings_andDisableWorkersInTests() {
    assertThat(props.enabled()).isFalse();
    assertThat(props.stream()).isEqualTo("jobs:stream");
    assertThat(props.group()).isEqualTo("job-workers");
    assertThat(props.workers()).isEqualTo(2);
    assertThat(props.maxAttempts()).isEqualTo(3);
    assertThat(props.minIdleMs()).isGreaterThan(10_000);
  }
}
```

`src/test/java/com/example/board/jobs/JobControllerTest.java`

```java
package com.example.board.jobs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.board.auth.jwt.JwtTokenProvider;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 단계 19: HTTP 계약만 검증 — Redis 작업은 가짜(MockitoBean)로 대체한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JobControllerTest {

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired JwtTokenProvider tokenProvider;

  @MockitoBean JobProducer producer;
  @MockitoBean JobStore store;
  @MockitoBean JobMonitor monitor;

  String token;

  @BeforeEach
  void setUp() {
    userRepository.save(new User("jobuser1", "jobuser1@example.com", "encoded", Role.USER));
    token = "Bearer " + tokenProvider.createToken("jobuser1");
  }

  private JobView queued(String id) {
    return new JobView(id, JobStatus.QUEUED, 0, null, null, null, "t", "t");
  }

  @Test
  void should_accept_with202_andQueuedStatus() throws Exception {
    given(producer.submit(any())).willReturn(queued("j1"));

    mockMvc.perform(post("/api/v1/jobs")
            .header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"durationSeconds\": 3, \"failTimes\": 0}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").value("j1"))
        .andExpect(jsonPath("$.status").value("QUEUED"));
  }

  @Test
  void should_reject_durationOver10() throws Exception {
    mockMvc.perform(post("/api/v1/jobs")
            .header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"durationSeconds\": 11, \"failTimes\": 0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @Test
  void should_reject_failTimesOver5() throws Exception {
    mockMvc.perform(post("/api/v1/jobs")
            .header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"durationSeconds\": 1, \"failTimes\": 6}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return404_forUnknownJob() throws Exception {
    given(store.find("nope")).willReturn(Optional.empty());

    mockMvc.perform(get("/api/v1/jobs/nope").header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
  }

  @Test
  void should_return401_withoutToken() throws Exception {
    mockMvc.perform(post("/api/v1/jobs")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"durationSeconds\": 1, \"failTimes\": 0}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_returnStats() throws Exception {
    given(monitor.stats()).willReturn(new JobStats(3, 1, List.of(), 0));

    mockMvc.perform(get("/api/v1/jobs/stats").header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.streamLength").value(3))
        .andExpect(jsonPath("$.pending").value(1));
  }
}
```

`JobFlowRedisTest` — 실제 Redis가 필요하다(없으면 건너뜀). 실행: `./gradlew test --rerun --tests '*JobFlowRedisTest'`(Redis 6379) 또는 `REDIS_PORT=6390 …`.

`src/test/java/com/example/board/jobs/JobFlowRedisTest.java`

```java
package com.example.board.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// 단계 19: 실제 Redis로 큐 흐름 전체를 검증한다(Redis 없으면 skip).
// 실행: REDIS_PORT=6390 ./gradlew test --rerun --tests '*JobFlowRedisTest'
class JobFlowRedisTest {

  private static final String HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
  private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

  // 테스트는 기다림을 줄이려고 min-idle 200ms, 일 단위 10ms로 줄인다.
  static final JobProperties PROPS = new JobProperties(
      true, "jobs:stream", "job-workers", "jobs:dead", 2, 3, 200, 5000, 1, 10);

  LettuceConnectionFactory factory;
  StringRedisTemplate redis;
  JobStore store;
  JobProducer producer;
  JobWorker worker;
  JobReclaimer reclaimer;
  JobMonitor monitor;

  @BeforeEach
  void setUp() {
    assumeTrue(reachable(), "Redis " + HOST + ":" + PORT + " 미기동 — 건너뜀");
    RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(HOST, PORT);
    config.setDatabase(14);
    factory = new LettuceConnectionFactory(config);
    factory.afterPropertiesSet();
    factory.start();
    redis = new StringRedisTemplate(factory);
    deleteJobKeys();
    store = new JobStore(redis, PROPS);
    producer = new JobProducer(redis, PROPS, store);
    worker = new JobWorker(redis, PROPS, store);
    reclaimer = new JobReclaimer(redis, PROPS, store, worker, Runnable::run);   // 테스트는 같은 스레드에서 즉시 실행
    monitor = new JobMonitor(redis, PROPS);
    JobQueueConfig.createGroupIfAbsent(redis, PROPS);
  }

  @AfterEach
  void tearDown() {
    if (factory != null) {
      deleteJobKeys();
      factory.destroy();
    }
  }

  @Test
  void should_createQueuedStatus_withTtl() {
    store.create("j1");

    JobView view = store.find("j1").orElseThrow();
    assertThat(view.status()).isEqualTo(JobStatus.QUEUED);
    assertThat(view.attempts()).isZero();
    assertThat(redis.getExpire(JobStore.key("j1"))).isPositive();
  }

  @Test
  void should_updateStatus_andCountAttempts() {
    store.create("j1");

    assertThat(store.incrementAttempts("j1")).isEqualTo(1);
    store.update("j1", JobStatus.RUNNING, Map.of("worker", "worker-1"));

    JobView view = store.find("j1").orElseThrow();
    assertThat(view.status()).isEqualTo(JobStatus.RUNNING);
    assertThat(view.worker()).isEqualTo("worker-1");
    assertThat(view.attempts()).isEqualTo(1);
  }

  @Test
  void should_returnEmpty_forUnknownJob() {
    assertThat(store.find("nope")).isEmpty();
  }

  @Test
  void should_queueJob_inStreamAndStatus() {
    JobView view = producer.submit(new JobSubmitRequest(3, 1));

    assertThat(view.status()).isEqualTo(JobStatus.QUEUED);
    assertThat(redis.opsForStream().size("jobs:stream")).isEqualTo(1);
    var records = redis.opsForStream().range("jobs:stream", Range.unbounded());
    assertThat(records.get(0).getValue())
        .containsEntry("jobId", view.jobId())
        .containsEntry("durationSeconds", "3")
        .containsEntry("failTimes", "1");
  }

  // XREADGROUP GROUP job-workers <consumer> COUNT 1 STREAMS jobs:stream > — 작업자가 하는 읽기를 테스트가 직접 한다
  MapRecord<String, Object, Object> readAs(String consumer) {
    var records = redis.opsForStream().read(
        Consumer.from(PROPS.group(), consumer),
        StreamReadOptions.empty().count(1),
        StreamOffset.create(PROPS.stream(), ReadOffset.lastConsumed()));
    return records.get(0);
  }

  static Map<String, String> fields(MapRecord<String, Object, Object> record) {
    Map<String, String> map = new HashMap<>();
    record.getValue().forEach((k, v) -> map.put(k.toString(), v.toString()));
    return map;
  }

  long pending() {
    return redis.opsForStream().pending(PROPS.stream(), PROPS.group()).getTotalPendingMessages();
  }

  @Test
  void should_createGroupIdempotently() {
    JobQueueConfig.createGroupIfAbsent(redis, PROPS);   // setUp에서 이미 한 번 — 두 번째도 예외 없이
    assertThat(redis.opsForStream().groups(PROPS.stream()).size()).isEqualTo(1);
  }

  @Test
  void should_markDone_andAck_onSuccess() {
    JobView job = producer.submit(new JobSubmitRequest(1, 0));
    var record = readAs("worker-1");
    assertThat(pending()).isEqualTo(1);           // 읽었지만 아직 ACK 전 → PEL에 있다

    boolean ok = worker.process("worker-1", record.getId().getValue(), fields(record));

    assertThat(ok).isTrue();
    assertThat(pending()).isZero();               // XACK로 PEL에서 빠졌다
    JobView done = store.find(job.jobId()).orElseThrow();
    assertThat(done.status()).isEqualTo(JobStatus.DONE);
    assertThat(done.worker()).isEqualTo("worker-1");
    assertThat(done.attempts()).isEqualTo(1);
  }

  @Test
  void should_leaveInPending_onFailure() {
    JobView job = producer.submit(new JobSubmitRequest(1, 1));
    var record = readAs("worker-1");

    boolean ok = worker.process("worker-1", record.getId().getValue(), fields(record));

    assertThat(ok).isFalse();
    assertThat(pending()).isEqualTo(1);           // ACK 안 함 → 재시도 대기
    JobView failed = store.find(job.jobId()).orElseThrow();
    assertThat(failed.status()).isEqualTo(JobStatus.RETRY_WAIT);
    assertThat(failed.error()).contains("의도된 실패");
  }

  void waitIdle() throws InterruptedException {
    Thread.sleep(PROPS.minIdleMs() + 50);
  }

  @Test
  void should_retryFailedJob_untilDone() throws InterruptedException {
    JobView job = producer.submit(new JobSubmitRequest(1, 1));     // 1번만 실패
    var record = readAs("worker-1");
    worker.process("worker-1", record.getId().getValue(), fields(record));
    waitIdle();

    reclaimer.reclaim();                                            // XPENDING → XCLAIM → 재실행

    JobView done = store.find(job.jobId()).orElseThrow();
    assertThat(done.status()).isEqualTo(JobStatus.DONE);
    assertThat(done.attempts()).isEqualTo(2);
    assertThat(done.worker()).isEqualTo(JobReclaimer.RECLAIMER);
    assertThat(pending()).isZero();
  }

  @Test
  void should_notReclaim_beforeMinIdle() {
    producer.submit(new JobSubmitRequest(1, 1));
    var record = readAs("worker-1");
    worker.process("worker-1", record.getId().getValue(), fields(record));

    reclaimer.reclaim();                                            // 아직 min-idle 전 → 건드리지 않음

    var entry = redis.opsForStream().pending(PROPS.stream(), PROPS.group(), Range.unbounded(), 10).get(0);
    assertThat(entry.getConsumerName()).isEqualTo("worker-1");
  }

  @Test
  void should_moveToDeadLetter_afterMaxAttempts() throws InterruptedException {
    JobView job = producer.submit(new JobSubmitRequest(1, 5));      // 계속 실패
    var record = readAs("worker-1");
    worker.process("worker-1", record.getId().getValue(), fields(record));   // 전달 1회
    waitIdle();
    reclaimer.reclaim();                                                     // 전달 2회(실패)
    waitIdle();
    reclaimer.reclaim();                                                     // 전달 3회(실패)
    waitIdle();

    reclaimer.reclaim();                                                     // 3회 = 한도 → DLQ

    JobView failed = store.find(job.jobId()).orElseThrow();
    assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
    assertThat(failed.attempts()).isEqualTo(3);
    assertThat(pending()).isZero();
    assertThat(redis.opsForStream().size(PROPS.deadStream())).isEqualTo(1);
  }

  @Test
  void should_reclaimAbandonedJob() throws InterruptedException {
    JobView job = producer.submit(new JobSubmitRequest(1, 0));
    readAs("worker-1");                     // 받아 놓고 처리 전에 "죽음" — process 호출 안 함
    waitIdle();

    reclaimer.reclaim();

    assertThat(store.find(job.jobId()).orElseThrow().status()).isEqualTo(JobStatus.DONE);
    assertThat(pending()).isZero();
  }

  @Test
  void should_reportStreamPendingConsumersAndDead() {
    producer.submit(new JobSubmitRequest(1, 0));
    producer.submit(new JobSubmitRequest(1, 0));
    readAs("worker-1");                                  // 1개만 가져가고 ACK 안 함

    JobStats stats = monitor.stats();

    assertThat(stats.streamLength()).isEqualTo(2);
    assertThat(stats.pending()).isEqualTo(1);
    assertThat(stats.consumers()).extracting(JobStats.ConsumerStat::name).contains("worker-1");
    assertThat(stats.deadLetters()).isZero();
  }

  @Test
  void should_reportZeros_whenNothingExists() {
    redis.delete(PROPS.stream());                         // 그룹째 사라진 상태

    JobStats stats = monitor.stats();

    assertThat(stats.streamLength()).isZero();
    assertThat(stats.pending()).isZero();
    assertThat(stats.consumers()).isEmpty();
  }

  // 실제 리스너 컨테이너로 분배 확인 — job을 미리 쌓아 두면, 한 번에 여러 개를 읽는 작업자가 전부 가져가 버린다(prefetch).
  @Test
  void should_distributeQueuedJobs_acrossWorkers() throws InterruptedException {
    JobProperties slow = new JobProperties(
        true, "jobs:stream", "job-workers", "jobs:dead", 2, 3, 200, 5000, 1, 100);
    JobWorker slowWorker = new JobWorker(redis, slow, store);
    List<String> ids = List.of(
        producer.submit(new JobSubmitRequest(2, 0)).jobId(),
        producer.submit(new JobSubmitRequest(2, 0)).jobId(),
        producer.submit(new JobSubmitRequest(2, 0)).jobId(),
        producer.submit(new JobSubmitRequest(2, 0)).jobId());

    var container = JobQueueConfig.createContainer(factory, slow, slowWorker);
    container.start();
    try {
      long deadline = System.currentTimeMillis() + 5000;
      while (System.currentTimeMillis() < deadline
          && !ids.stream().allMatch(id -> store.find(id).orElseThrow().status() == JobStatus.DONE)) {
        Thread.sleep(50);
      }
    } finally {
      container.stop();
    }

    var workers = ids.stream().map(id -> store.find(id).orElseThrow().worker()).collect(Collectors.toSet());
    assertThat(ids).allMatch(id -> store.find(id).orElseThrow().status() == JobStatus.DONE);
    assertThat(workers).containsExactlyInAnyOrder("worker-1", "worker-2");
  }

  // redis-cli로 직접 넣은 레코드처럼 필수 필드가 빠진 "독약(poison)" 레코드 — 작업자가 예외로 죽지 않고 보관함으로 보낸다
  @Test
  void should_sendMalformedRecord_toDeadLetter_withoutThrowing() {
    redis.opsForStream().add(PROPS.stream(), Map.of("hello", "world"));
    var record = readAs("worker-1");

    boolean ok = worker.process("worker-1", record.getId().getValue(), fields(record));

    assertThat(ok).isFalse();
    assertThat(pending()).isZero();                                   // 다시 시도해도 소용없으니 ACK
    assertThat(redis.opsForStream().size(PROPS.deadStream())).isEqualTo(1);
  }

  // 독약 레코드가 PEL에 있어도 재시도 담당은 멈추지 않고 다른 job을 계속 처리한다
  @Test
  void should_keepReclaiming_whenMalformedRecordIsPending() throws InterruptedException {
    redis.opsForStream().add(PROPS.stream(), Map.of("hello", "world"));
    readAs("worker-1");                                               // 독약을 받아 놓고 죽음
    JobView job = producer.submit(new JobSubmitRequest(1, 0));
    readAs("worker-1");                                               // 정상 job도 받아 놓고 죽음
    waitIdle();

    reclaimer.reclaim();

    assertThat(store.find(job.jobId()).orElseThrow().status()).isEqualTo(JobStatus.DONE);
    assertThat(pending()).isZero();
  }

  // 실행 스레드가 모두 바쁘면 넘겨받지 않는다 — 넘겨받고 줄 서서 기다리는 동안 idle이 다시 쌓여
  // 같은 레코드를 또 넘겨받는(중복 실행) 일을 막는다. 스레드 수(RETRY_THREADS)만큼만 넘겨받아야 한다.
  @Test
  void should_claimOnlyAsManyAsFreeRetryThreads() throws InterruptedException {
    List<Runnable> queued = new java.util.ArrayList<>();
    JobReclaimer busy = new JobReclaimer(redis, PROPS, store, worker, queued::add);   // 받기만 하고 실행 안 함
    for (int i = 0; i < 3; i++) {
      producer.submit(new JobSubmitRequest(1, 0));
      readAs("worker-1");                                         // 3개 모두 받아 놓고 죽음
    }
    waitIdle();

    busy.reclaim();

    assertThat(queued).hasSize(JobReclaimer.RETRY_THREADS);
    var owners = redis.opsForStream().pending(PROPS.stream(), PROPS.group(), Range.unbounded(), 10)
        .stream().map(p -> p.getConsumerName()).toList();
    assertThat(owners).containsOnly(JobReclaimer.RECLAIMER, "worker-1");
    assertThat(owners.stream().filter("worker-1"::equals)).hasSize(1);   // 나머지 1개는 다음 기회에
  }

  // 실습 중 DEL jobs:stream 같은 조작으로 읽기 오류(NOGROUP)가 나도 작업자 구독이 죽지 않아야 한다.
  // 기본값(cancelOnError=true)이면 오류 한 번에 구독이 영구 취소돼 이후 job이 영원히 QUEUED로 남는다.
  @Test
  void should_keepWorking_afterReadErrors() throws InterruptedException {
    JobProperties fast = new JobProperties(
        true, "jobs:stream", "job-workers", "jobs:dead", 2, 3, 200, 5000, 1, 10);
    var container = JobQueueConfig.createContainer(factory, fast, new JobWorker(redis, fast, store));
    container.start();
    try {
      Thread.sleep(300);
      redis.delete(fast.stream());                     // 그룹째 삭제 → 다음 XREADGROUP이 NOGROUP 오류
      Thread.sleep(2500);                              // 폴링 한 바퀴(2초) 이상 오류를 겪게 한다
      JobQueueConfig.createGroupIfAbsent(redis, fast);
      JobView job = producer.submit(new JobSubmitRequest(1, 0));

      long deadline = System.currentTimeMillis() + 6000;
      while (System.currentTimeMillis() < deadline
          && store.find(job.jobId()).orElseThrow().status() != JobStatus.DONE) {
        Thread.sleep(100);
      }
      assertThat(store.find(job.jobId()).orElseThrow().status()).isEqualTo(JobStatus.DONE);
    } finally {
      container.stop();
    }
  }

  void deleteJobKeys() {
    Set<String> keys = redis.keys("jobs:*");
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
  }

  static boolean reachable() {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(HOST, PORT), 300);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
```

---

## 9. 정답 브랜치의 커밋 순서 — 실제로 어떻게 만들어졌나

| 커밋 | 내용 | 워크북 Step |
|------|------|-------------|
| `f64550f` | feat(jobs): 단계 19 설정 — app.jobs.*, JobProperties, JOB_NOT_FOUND | 1 |
| `c441f7a` | feat(jobs): 상태 저장소 — Redis Hash jobs:{id} + TTL | 2 |
| `e44529a` | feat(jobs): 생산자 + API — XADD 후 202, 상태 조회 | 3 |
| `a6cf567` | feat(jobs): 작업자 2개 + 리스너 컨테이너 — XREADGROUP, 성공 시 XACK | 4 |
| `965a313` | feat(jobs): 재시도 담당 — XPENDING+XCLAIM 넘겨받기, 한도 초과 시 jobs:dead | 5 |
| `dbd2cec` | feat(jobs): 통계 API — XLEN·XPENDING·XINFO CONSUMERS·DLQ 길이 | 7 |
| `7564c89` | fix(jobs): 작업자가 쌓인 job을 몽땅 가져가던 문제 — batchSize(1) | 4 (prefetch) |
| `8a0d17a` | fix(jobs): 필수 필드 없는 레코드(독약 메시지)가 작업자·재시도 담당을 멈추던 문제 | 4·5 |
| `d95b95d` | fix(jobs): 재시도 경로 중복 실행 — 빈 실행 스레드가 있을 때만 XCLAIM | 5 |
| `f915a7b` | fix(jobs): 읽기 오류 한 번에 작업자 구독이 영구 취소되던 문제 + 오류 재시도 간격 | 4 |

뒤의 `fix` 네 개가 이 실습의 진짜 교훈이다 — 모두 "테스트는 통과했는데 실제로 돌려 보거나 코드 리뷰를 받아서" 찾았고, 재현 테스트(RED)를 먼저 쓴 뒤 고쳤다.
