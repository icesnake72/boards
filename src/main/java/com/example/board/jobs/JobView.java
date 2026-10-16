package com.example.board.jobs;

import java.util.Map;

public record JobView(
    String jobId,
    JobStatus status,
    long attempts,
    String worker,
    String result,
    String error,
    String createdAt,
    String updatedAt
) {

  // Redis Hash(HGETALL) → 응답. 값은 모두 문자열로 저장되어 있다.
  static JobView from(String jobId, Map<Object, Object> hash) {
    return new JobView(
        jobId,
        JobStatus.valueOf((String) hash.get("status")),
        Long.parseLong((String) hash.getOrDefault("attempts", "0")),
        (String) hash.get("worker"),
        (String) hash.get("result"),
        (String) hash.get("error"),
        (String) hash.get("createdAt"),
        (String) hash.get("updatedAt"));
  }
}
