package com.example.board.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 단계 19: job 상태를 Redis Hash jobs:{jobId}에 둔다(큐와 같은 Redis, TTL로 자동 정리).
@Component
@RequiredArgsConstructor
public class JobStore {

  private final StringRedisTemplate redis;
  private final JobProperties props;

  static String key(String jobId) {
    return "jobs:" + jobId;
  }

  public void create(String jobId) {
    String now = Instant.now().toString();
    Map<String, String> fields = new HashMap<>();
    fields.put("status", JobStatus.QUEUED.name());
    fields.put("attempts", "0");
    fields.put("createdAt", now);
    fields.put("updatedAt", now);
    write(jobId, fields);
  }

  public void update(String jobId, JobStatus status, Map<String, String> extra) {
    Map<String, String> fields = new HashMap<>(extra);
    fields.put("status", status.name());
    fields.put("updatedAt", Instant.now().toString());
    write(jobId, fields);
  }

  // HINCRBY — 원자적 증가. 몇 번째 시도인지 화면에 보여 주는 값이다.
  public long incrementAttempts(String jobId) {
    Long value = redis.opsForHash().increment(key(jobId), "attempts", 1);
    return value == null ? 0 : value;
  }

  public Optional<JobView> find(String jobId) {
    Map<Object, Object> hash = redis.opsForHash().entries(key(jobId));
    return hash.isEmpty() ? Optional.empty() : Optional.of(JobView.from(jobId, hash));
  }

  private void write(String jobId, Map<String, String> fields) {
    redis.opsForHash().putAll(key(jobId), fields);
    redis.expire(key(jobId), Duration.ofHours(props.statusTtlHours()));
  }
}
