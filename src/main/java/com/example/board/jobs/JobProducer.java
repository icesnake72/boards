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
