package com.example.board.jobs;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 단계 19: JobWorker — job 하나를 "실제로 처리하는 방법"이 들어 있는 클래스.
//
// 한 줄 요약
//   큐에서 꺼낸 job 한 건을 받아 일을 하고, 성공하면 "완료(ACK)" 도장을 찍는다. 실패하면 도장을 찍지 않는다.
//
// 식당 비유
//   요리사들이 함께 보는 "조리법". 요리사(작업자 스레드 worker-1, worker-2, 그리고 재시도 담당)가
//   주문표를 한 장 들고 와서 이 조리법대로 요리한다.
//
// 어떤 객체인가
//   @Component라서 Spring이 앱 시작 때 딱 1개 만든다(싱글톤). worker-1 스레드도, worker-2 스레드도,
//   재시도 스레드도 모두 이 객체 하나의 process()를 호출한다. 스레드가 아니라 "처리 방법"일 뿐이다.
//   여러 스레드가 동시에 불러도 안전하다 — 필드는 모두 바뀌지 않는(final) 도구들이고,
//   job마다 다른 값(jobId, 시도 횟수 등)은 전부 메서드 안의 지역 변수에만 담기 때문이다.
//
// 누가, 언제 부르나
//   1) 작업자 스레드(worker-1, worker-2): Redis에서 새 job을 하나 받을 때마다          → JobQueueConfig에서 등록
//   2) 재시도 담당(JobReclaimer): 방치된 job을 넘겨받아 다시 시킬 때(이름 "reclaimer") → JobReclaimer.reclaimOne
//
// 가장 중요한 규칙
//   "성공했을 때만 ACK한다." ACK를 안 하면 job은 Redis의 처리 중 목록(PEL)에 남아 있고,
//   나중에 재시도 담당이 그것을 찾아 다시 시킨다. 그래서 실패하거나 처리 중에 서버가 죽어도 job이 사라지지 않는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class JobWorker {

  private final StringRedisTemplate redis;   // Redis에 명령을 보내는 도구(ACK, 실패 보관함 기록에 사용)
  private final JobProperties props;         // 설정값(스트림 이름, 그룹 이름 등)
  private final JobStore store;              // job 상태판(Hash jobs:{jobId})을 읽고 쓰는 도구

  // job 한 건을 처리한다.
  //
  // 파라미터
  //   workerName : 누가 처리하나 — "worker-1", "worker-2", "reclaimer" (로그와 상태판에 기록용)
  //   recordId   : Redis가 이 job에 붙인 큐 안의 번호(예: 1792172942491-0) — 완료 도장(ACK)을 찍을 때 필요
  //   fields     : 주문표 내용 — {jobId=…, durationSeconds=3, failTimes=0}
  //
  // 돌려주는 값
  //   true  = 성공해서 ACK까지 했다
  //   false = 실패했다(ACK 안 함 → 나중에 재시도) 또는 주문표가 잘못돼서 버렸다
  //
  // 처리 순서
  //   (1) 주문표 읽기 → (2) 잘못된 주문표면 버리기 → (3) "처리 중"으로 표시
  //   → (4) 일하기 → (5) 성공이면 "완료" + ACK / 실패면 "재시도 대기"(ACK 안 함)
  public boolean process(String workerName, String recordId, Map<String, String> fields) {
    // (1) 주문표 읽기. Redis Stream에는 모든 값이 문자열로 들어 있어서 숫자로 바꿔 쓴다.
    String jobId = fields.get("jobId");
    Integer duration = parseOrNull(fields.get("durationSeconds"));   // 몇 초 동안 일할지
    Integer failTimes = parseOrNull(fields.get("failTimes"));        // 처음 몇 번은 일부러 실패할지(데모용)

    // (2) 필요한 값이 하나라도 없으면(예: redis-cli로 아무렇게나 넣은 레코드) 몇 번을 다시 해도 실패한다.
    //     재시도로 시간을 버리지 않고 바로 실패 보관함에 넣고 끝낸다.
    if (jobId == null || duration == null || failTimes == null) {
      discard(workerName, recordId, fields);
      return false;
    }

    // (3) 상태판에 "몇 번째 시도인지"를 1 올리고, 상태를 RUNNING(처리 중)으로 바꾼다.
    long attempt = store.incrementAttempts(jobId);
    store.update(jobId, JobStatus.RUNNING, Map.of("worker", workerName));
    log.info("[{}] start job={} attempt={} record={}", workerName, jobId, attempt, recordId);

    try {
      // (4) 일하기. 이 데모에서는 durationSeconds초 동안 잠자는 것이 "일"이다.
      //     실무라면 이 자리에 이미지 변환, 메일 발송, 보고서 생성 같은 진짜 작업이 들어간다.
      work(duration);

      // 데모용 실패 장치: 처음 failTimes번의 시도는 일부러 실패시킨다.
      //   예) failTimes=1 → 1번째 시도는 실패, 2번째 시도(재시도)는 성공
      if (attempt <= failTimes) {
        throw new IllegalStateException("의도된 실패 " + attempt + "/" + failTimes);
      }

      // (5-성공) 상태판을 DONE(완료)으로 바꾸고, Redis에 "이 job 끝났다"는 도장(XACK)을 찍는다.
      //          ACK하면 이 job은 처리 중 목록(PEL)에서 빠진다 — 더는 재시도 대상이 아니다.
      store.update(jobId, JobStatus.DONE, Map.of(
          "result", workerName + "가 " + duration + "초 작업 완료", "error", ""));
      redis.opsForStream().acknowledge(props.stream(), props.group(), recordId);
      log.info("[{}] done job={} → XACK", workerName, jobId);
      return true;

    } catch (InterruptedException e) {
      // 앱이 꺼지는 중이라 잠자던 스레드가 깨워졌다. 일을 다 못 했으니 ACK하지 않는다.
      //   → job은 처리 중 목록에 남고, 앱이 다시 뜨면 재시도 담당이 이어서 처리한다.
      Thread.currentThread().interrupt();
      log.warn("[{}] interrupted job={} — ACK 안 함(PEL에 남김)", workerName, jobId);
      return false;

    } catch (RuntimeException e) {
      // (5-실패) 상태판을 RETRY_WAIT(재시도 대기)로 바꾸고 에러 내용을 적는다. ACK는 하지 않는다.
      //          → 15초쯤 지나면 재시도 담당이 이 job을 찾아 다시 시킨다(최대 3번까지).
      //          여기서 예외를 밖으로 던지지 않는 이유: 작업자 스레드의 반복 루프가 멈추지 않게 하려고.
      store.update(jobId, JobStatus.RETRY_WAIT, Map.of("error", String.valueOf(e.getMessage())));
      log.warn("[{}] fail job={} attempt={} cause={} — ACK 안 함(재시도 대기)",
          workerName, jobId, attempt, e.getMessage());
      return false;
    }
  }

  // 잘못된 주문표("독약" 메시지)를 버리는 메서드.
  //   1) 실패 보관함(jobs:dead)에 "무엇이, 왜 버려졌는지" 기록하고
  //   2) 원래 큐에서는 ACK해서 처리 중 목록에서 뺀다(다시 시도해 봐야 소용없으므로).
  //   예외를 밖으로 던지지 않는다 — 던지면 작업자 스레드의 구독이 멈출 수 있다.
  private void discard(String workerName, String recordId, Map<String, String> fields) {
    redis.opsForStream().add(props.deadStream(), Map.of(
        "originalId", recordId,
        "error", "필수 필드 누락",
        "fields", fields.toString()));
    redis.opsForStream().acknowledge(props.stream(), props.group(), recordId);
    log.warn("[{}] malformed record={} fields={} → {} (ACK)", workerName, recordId, fields, props.deadStream());
  }

  // 문자열을 숫자로 바꾼다. 값이 없거나 숫자가 아니면 null(= 잘못된 주문표)을 돌려준다.
  private static Integer parseOrNull(String value) {
    try {
      return value == null ? null : Integer.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // "일하는 척" — units번 잠든다. 1번 = work-unit-ms(기본 1000ms = 1초).
  //   테스트는 work-unit-ms를 10ms로 줄여서 빨리 끝나게 한다.
  private void work(int units) throws InterruptedException {
    for (int i = 0; i < units; i++) {
      Thread.sleep(props.workUnitMs());
    }
  }
}
