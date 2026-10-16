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
    int duration = Integer.parseInt(fields.get("durationSeconds"));
    int failTimes = Integer.parseInt(fields.get("failTimes"));
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

  // "일하는 척" — 초 단위로 잠든다(테스트는 work-unit-ms를 10ms로 줄인다).
  private void work(int units) throws InterruptedException {
    for (int i = 0; i < units; i++) {
      Thread.sleep(props.workUnitMs());
    }
  }
}
