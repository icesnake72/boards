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
