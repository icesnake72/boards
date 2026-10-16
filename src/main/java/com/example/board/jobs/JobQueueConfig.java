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
            .errorHandler(e -> log.warn("[jobs] 리스너 오류: {}", e.toString()))
            .build();
    StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
        StreamMessageListenerContainer.create(connectionFactory, options);
    for (int i = 1; i <= props.workers(); i++) {
      String name = "worker-" + i;
      // receive(...) = autoAck 끔. ">"(lastConsumed)로 아직 누구도 받지 않은 새 job만 읽는다.
      container.receive(Consumer.from(props.group(), name),
          StreamOffset.create(props.stream(), ReadOffset.lastConsumed()),
          record -> worker.process(name, record.getId().getValue(), record.getValue()));
    }
    return container;
  }

  // 재시도 담당이 넘겨받은 job을 실행할 전용 스레드(스케줄러 스레드를 막지 않게).
  @Bean
  public ThreadPoolTaskExecutor jobRetryExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(2);
    executor.setQueueCapacity(100);
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
