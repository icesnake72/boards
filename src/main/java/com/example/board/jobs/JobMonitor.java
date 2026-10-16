package com.example.board.jobs;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 단계 19: 큐 상태를 한눈에 — redis-cli의 XLEN / XPENDING / XINFO CONSUMERS를 API로 옮긴 것.
@Component
@RequiredArgsConstructor
public class JobMonitor {

  private final StringRedisTemplate redis;
  private final JobProperties props;

  public JobStats stats() {
    long length = sizeOf(props.stream());
    long dead = sizeOf(props.deadStream());
    try {
      PendingMessagesSummary summary = redis.opsForStream().pending(props.stream(), props.group());
      List<JobStats.ConsumerStat> consumers = redis.opsForStream()
          .consumers(props.stream(), props.group()).stream()
          .map(c -> new JobStats.ConsumerStat(c.consumerName(), c.pendingCount(), c.idleTimeMs()))
          .toList();
      return new JobStats(length, summary.getTotalPendingMessages(), consumers, dead);
    } catch (DataAccessException e) {
      // 스트림·그룹이 아직 없으면(NOGROUP) 0으로 본다
      return new JobStats(length, 0, List.of(), dead);
    }
  }

  private long sizeOf(String key) {
    Long size = redis.opsForStream().size(key);
    return size == null ? 0 : size;
  }
}
