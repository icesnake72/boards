package com.example.board.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
