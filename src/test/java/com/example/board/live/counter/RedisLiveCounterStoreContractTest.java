package com.example.board.live.counter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// 단계 18: 같은 계약을 실제 Redis 구현(Lua 2개 포함)으로 실행한다 — 상위 클래스의 테스트가 그대로 상속된다.
// Redis가 없으면(CI, Redis 미기동 로컬) 건너뛴다. 실행: REDIS_PORT=6390 ./gradlew test --tests '*RedisLiveCounterStore*'
// 개발 데이터와 섞이지 않게 DB 15번을 쓰고, 테스트가 만든 live:* 키만 지운다.
class RedisLiveCounterStoreContractTest extends LiveCounterStoreContractTest {

  private static final String HOST = System.getenv().getOrDefault("REDIS_HOST", "localhost");
  private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
  private static final int TEST_DATABASE = 15;

  private LettuceConnectionFactory factory;
  private StringRedisTemplate redis;

  @Override
  protected LiveCounterStore createStore() {
    // Lettuce의 연결 타임아웃(수 초)을 테스트마다 기다리지 않도록 TCP로 먼저 짧게 확인한다.
    assumeTrue(reachable(), "Redis " + HOST + ":" + PORT + " 미기동 — Redis 계약 테스트 건너뜀");
    RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(HOST, PORT);
    config.setDatabase(TEST_DATABASE);
    factory = new LettuceConnectionFactory(config);
    factory.afterPropertiesSet();
    factory.start();
    redis = new StringRedisTemplate(factory);
    deleteTestKeys();
    return new RedisLiveCounterStore(redis);
  }

  @AfterEach
  void tearDown() {
    if (factory != null) {
      deleteTestKeys();
      factory.destroy();
    }
  }

  @Test
  void should_setTtlOnEveryWrittenKey() {
    store.addQuestion(1L, 10L);
    store.toggleLike(1L, 10L, 100L);
    store.vote(5L, 51L, 100L);

    for (String key : Set.of("live:event:1:questions", "live:event:1:user:100:likes",
        "live:poll:5:voters", "live:poll:5:counts")) {
      assertThat(redis.getExpire(key)).as(key).isPositive();
    }
  }

  private void deleteTestKeys() {
    Set<String> keys = redis.keys("live:*");
    if (keys != null && !keys.isEmpty()) {
      redis.delete(keys);
    }
  }

  private static boolean reachable() {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(HOST, PORT), 300);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
