package com.example.board.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
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
