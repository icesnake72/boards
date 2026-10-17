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

// 단계 19: 작업 큐의 "꺼내는 쪽(소비자)"을 조립하는 설정 클래스.
//
// 이 클래스가 앱 기동 시 하는 일은 세 가지다.
//   1) Redis에 consumer group(job-workers)을 만든다 — 이미 있으면 그대로 쓴다.   → createGroupIfAbsent()
//   2) 리스너 컨테이너를 만들고, 작업자 이름표(worker-1, worker-2…)마다
//      "XREADGROUP으로 job을 하나 받아 → JobWorker.process()를 호출"하는 반복 작업을 등록·시작한다.
//                                                                              → jobListenerContainer(), createContainer()
//   3) 재시도 담당(JobReclaimer)이 넘겨받은 job을 실행할 전용 스레드 풀을 만든다.  → jobRetryExecutor()
//
// 이 클래스는 job을 "처리"하지 않는다. 처리 로직은 JobWorker 하나(싱글톤 빈)에 있고,
// 여기서 만든 스레드들이 그 객체를 호출할 뿐이다.
//   예) 스레드 SimpleAsyncTaskExecutor-1이 worker-1 이름으로 XREADGROUP → JobWorker.process("worker-1", …)
//       스레드 SimpleAsyncTaskExecutor-2가 worker-2 이름으로 XREADGROUP → JobWorker.process("worker-2", …)
//
// @ConditionalOnProperty: app.jobs.enabled=false(테스트 설정)이면 이 클래스의 빈이 하나도 만들어지지 않는다.
//   → 테스트는 Redis 없이도 Spring 컨텍스트가 뜬다. 키가 아예 없으면(matchIfMissing) 켜진 것으로 본다.
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobQueueConfig {

  // 앱 기동 시 한 번 실행되어 "작업자들이 일을 시작한 상태"의 컨테이너를 빈으로 등록한다.
  //
  // 실행 순서가 중요하다: 그룹부터 만들고(1) → 컨테이너를 조립하고(2) → 시작한다(3).
  //   그룹이 없는 상태에서 XREADGROUP을 보내면 NOGROUP 오류가 나기 때문이다.
  //
  // destroyMethod = "stop": 앱이 종료될 때 Spring이 container.stop()을 불러 폴링을 멈춘다.
  //   이때 처리 중이던 job은 ACK되지 않은 채 PEL에 남고, 재기동 후 재시도 담당이 이어서 처리한다(at-least-once).
  //
  // 파라미터는 모두 Spring이 주입한다.
  //   RedisConnectionFactory: Spring Boot가 만든 Redis 연결 공장(컨테이너가 블로킹 읽기용 연결을 여기서 얻는다)
  //   StringRedisTemplate:    그룹 생성 같은 일반 명령용
  //   JobProperties:          app.jobs.* 설정
  //   JobWorker:              실제 처리 로직(싱글톤) — 모든 작업자 스레드가 이 객체 하나를 함께 쓴다
  @Bean(destroyMethod = "stop")
  public StreamMessageListenerContainer<String, MapRecord<String, String, String>> jobListenerContainer(
      RedisConnectionFactory connectionFactory, StringRedisTemplate redis,
      JobProperties props, JobWorker worker) {
    createGroupIfAbsent(redis, props);                                  // (1) 그룹 준비
    StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
        createContainer(connectionFactory, props, worker);              // (2) 작업자 N명 등록
    container.start();                                                  // (3) 폴링 스레드 시작 — 이 순간부터 job을 꺼낸다
    log.info("[jobs] 리스너 시작: stream={} group={} workers={}",
        props.stream(), props.group(), props.workers());
    return container;
  }

  // 리스너 컨테이너를 조립한다(시작은 하지 않는다). 테스트가 같은 구성으로 실제 분배를 검증할 수 있게 static으로 분리했다.
  //
  // StreamMessageListenerContainer란?
  //   Spring Data Redis가 제공하는 "스트림 구독 관리자". 등록된 구독(StreamReadRequest)마다
  //   전용 스레드에서 다음 루프를 대신 돌려 준다.
  //       while (실행 중) {
  //         records = XREADGROUP GROUP <group> <consumer> COUNT 1 BLOCK 2000 STREAMS <stream> >
  //         for (record : records) listener.onMessage(record);   // ← 여기서 JobWorker.process 호출
  //       }
  //   우리가 while 루프·스레드·연결 관리를 직접 짜지 않아도 된다.
  //
  // 제네릭 <String, MapRecord<String, String, String>>:
  //   스트림 키가 String이고, 레코드는 필드·값이 모두 String인 Map(jobId, durationSeconds, failTimes)이라는 뜻.
  static StreamMessageListenerContainer<String, MapRecord<String, String, String>> createContainer(
      RedisConnectionFactory connectionFactory, JobProperties props, JobWorker worker) {

    // [컨테이너 공통 읽기 옵션]
    StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
        StreamMessageListenerContainerOptions.builder()
            // XREADGROUP … BLOCK 2000: 새 job이 없으면 Redis 쪽에서 최대 2초 기다렸다가 빈손으로 돌아온다.
            //   → 바쁜 대기(busy loop) 없이, 새 job이 들어오면 거의 즉시 깨어난다.
            .pollTimeout(Duration.ofSeconds(2))
            // XREADGROUP … COUNT 1: 한 번 읽을 때 하나만 가져간다.
            //   지정하지 않으면 쌓여 있던 job을 한 작업자가 몽땅 가져가(prefetch) 자기 처리 중 목록(PEL)에 넣고,
            //   다른 작업자는 논다. 실제 실행에서 job 4개가 9초 걸리던 것이 이 설정 후 6초로 줄었다.
            .batchSize(1)
            // 읽기 중 오류(Redis 단절, 그룹 삭제로 인한 NOGROUP 등)가 나면 이 메서드가 호출된다 → 아래 backoffOnError
            .errorHandler(JobQueueConfig::backoffOnError)
            .build();

    StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
        StreamMessageListenerContainer.create(connectionFactory, options);

    // [작업자 이름표마다 구독 하나씩 등록]
    // worker-1, worker-2는 클래스가 아니라 Redis consumer group 안의 "이름"이다.
    // Redis는 이 이름으로 "어떤 레코드를 누가 가져갔는지(PEL의 주인)"를 기록한다.
    // 구독 하나 = 폴링 스레드 하나이므로, workers 설정값만큼 동시에 job이 처리된다.
    for (int i = 1; i <= props.workers(); i++) {
      String name = "worker-" + i;

      StreamReadRequest<String> request = StreamReadRequest
          // 어디서부터 읽을지: ReadOffset.lastConsumed() = XREADGROUP의 ">"
          //   "그룹 안에서 아직 아무에게도 전달되지 않은 새 레코드"만 받는다.
          //   (이미 누가 받아 간 PEL 항목은 받지 않는다 — 그것은 재시도 담당 JobReclaimer의 몫)
          .builder(StreamOffset.create(props.stream(), ReadOffset.lastConsumed()))
          // 누구로서 읽을지: 그룹 job-workers의 소비자 worker-N
          .consumer(Consumer.from(props.group(), name))
          // 자동 ACK 끔: 컨테이너가 받자마자 ACK하면, 처리 중 실패하거나 죽었을 때 job이 사라진다.
          //   ACK는 JobWorker가 "성공했을 때만" 직접 한다 → 실패·다운 시 PEL에 남아 재시도된다.
          .autoAcknowledge(false)
          // 오류가 나도 구독을 취소하지 않음: 기본값(true)이면 읽기 오류 한 번에 이 작업자의 구독이 영구 취소된다.
          //   앱은 멀쩡해 보이는데 새 job이 영원히 QUEUED로 남는 "조용한 죽음"이 된다.
          .cancelOnError(e -> false)
          .build();

      // 리스너(람다): 컨테이너가 레코드를 받을 때마다 폴링 스레드에서 호출한다.
      //   record.getId()    → 스트림 레코드 id(예: 1792172942491-0) — ACK할 때 필요
      //   record.getValue() → 필드 Map(jobId, durationSeconds, failTimes)
      //   name              → 어느 작업자 이름으로 처리했는지(로그·상태 Hash의 worker 필드)
      container.register(request,
          record -> worker.process(name, record.getId().getValue(), record.getValue()));
    }
    return container;
  }

  // 컨테이너의 오류 처리기. 읽기 오류가 날 때마다 "그 폴링 스레드"에서 호출된다.
  //
  // 왜 1초 쉬는가: 오류가 계속되는 상황(예: 실습 중 DEL jobs:stream으로 그룹이 사라짐)에서는
  //   폴링 루프가 쉬지 않고 재시도해 경고 로그가 초당 수백 줄 쌓인다(실측: 2.5초에 약 1000줄).
  //   오류 처리기가 폴링 스레드 위에서 돌기 때문에, 여기서 잠들면 그 자체가 재시도 간격(backoff)이 된다(→ 8줄).
  //   그룹을 다시 만들면(XGROUP CREATE … MKSTREAM) 다음 읽기부터 정상으로 돌아온다.
  private static void backoffOnError(Throwable e) {
    log.warn("[jobs] 리스너 오류(1초 후 재시도): {}", e.toString());
    try {
      Thread.sleep(1000);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();   // 종료 중 인터럽트는 삼키지 않고 표시를 되살려 둔다
    }
  }

  // 재시도 담당(JobReclaimer)이 넘겨받은 job을 실행할 전용 스레드 풀(이름: job-retry-1, job-retry-2).
  //
  // 왜 따로 두는가: JobReclaimer.reclaim()은 @Scheduled로 "scheduling-1" 스레드에서 도는데,
  //   이 스레드는 단계 18의 SSE heartbeat와 공유한다. 10초짜리 job을 거기서 직접 실행하면
  //   heartbeat가 밀려 라이브 화면이 "재연결 중"이 된다. 그래서 재실행만 이 풀에 맡긴다.
  //
  // 스레드 수 = JobReclaimer.RETRY_THREADS(2). 재시도 담당은 빈 스레드가 있을 때만 job을 넘겨받으므로(세마포어),
  //   넘겨받은 job은 곧바로 실행이 시작된다 → "방치 기준 15초 > 최대 작업 10초" 규칙이 재시도 경로에서도 성립한다.
  @Bean
  public ThreadPoolTaskExecutor jobRetryExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(JobReclaimer.RETRY_THREADS);
    executor.setMaxPoolSize(JobReclaimer.RETRY_THREADS);
    // 대기열은 거의 쓰이지 않는다 — 스레드가 일을 마치고 세마포어를 반납한 직후, 스레드 자체가 풀에 돌아가기 전의 짧은 틈만 흡수한다.
    executor.setQueueCapacity(JobReclaimer.RETRY_THREADS);
    executor.setThreadNamePrefix("job-retry-");
    executor.initialize();
    return executor;
  }

  // consumer group을 만든다. 이미 있으면 아무것도 하지 않는다.
  //
  // 실제 명령: XGROUP CREATE jobs:stream job-workers 0 MKSTREAM
  //   0        : 스트림의 처음부터 읽는 그룹 — 그룹이 생기기 전에 쌓인 job(예: 작업자 없이 넣어 둔 job)도 처리된다.
  //              ("$"로 만들면 그룹 생성 이후에 들어온 job만 읽는다)
  //   MKSTREAM : 스트림이 아직 없으면 빈 스트림을 함께 만든다(Spring의 createGroup이 붙여 준다).
  //
  // 재기동할 때마다 그룹은 이미 있으므로 Redis는 "BUSYGROUP Consumer Group name already exists" 오류를 돌려준다.
  //   이것은 정상 상황이라 삼키고, 그 밖의 오류(연결 실패 등)는 다시 던져 기동을 멈춘다(fail-fast).
  //   Spring이 Lettuce 예외를 DataAccessException으로 감싸므로, 가장 안쪽 원인의 메시지로 판별한다.
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
