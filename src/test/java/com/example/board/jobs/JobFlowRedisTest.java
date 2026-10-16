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
