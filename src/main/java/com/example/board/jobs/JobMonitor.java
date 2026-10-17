package com.example.board.jobs;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 단계 19: JobMonitor — 작업 큐가 지금 어떤 상태인지 숫자로 보여 주는 클래스.
//
// 한 줄 요약
//   Redis에 몇 가지를 물어봐서(큐 길이, 처리 중 개수, 작업자별 현황, 실패 보관함 개수)
//   JobStats 하나로 묶어 돌려준다. GET /api/v1/jobs/stats가 이 결과를 그대로 응답한다.
//
// 식당 비유
//   주방 벽의 "상황판". 지금까지 들어온 주문 수, 요리 중인 주문 수,
//   요리사별로 몇 개를 들고 있고 얼마나 쉬었는지, 처리 불가 보관함에 몇 개가 있는지를 보여 준다.
//   상황판은 보여 주기만 하고 아무것도 바꾸지 않는다(읽기 전용).
//
// redis-cli로 같은 것을 보려면
//   XLEN jobs:stream                              → streamLength
//   XPENDING jobs:stream job-workers              → pending
//   XINFO CONSUMERS jobs:stream job-workers       → consumers
//   XLEN jobs:dead                                → deadLetters
@Component
@RequiredArgsConstructor
public class JobMonitor {

  private final StringRedisTemplate redis;   // Redis 명령 도구
  private final JobProperties props;         // 스트림·그룹 이름

  public JobStats stats() {
    // 큐에 지금까지 들어온 job 수. 주의: 완료(ACK)돼도 레코드는 지워지지 않으므로 "누적" 개수다.
    long length = sizeOf(props.stream());
    // 실패 보관함에 들어간 job 수
    long dead = sizeOf(props.deadStream());
    try {
      // 처리 중(ACK 전) job 총 개수
      PendingMessagesSummary summary = redis.opsForStream().pending(props.stream(), props.group());
      // 작업자별 현황: 이름, 들고 있는 job 수, 마지막 활동 후 지난 시간(ms)
      List<JobStats.ConsumerStat> consumers = redis.opsForStream()
          .consumers(props.stream(), props.group()).stream()
          .map(c -> new JobStats.ConsumerStat(c.consumerName(), c.pendingCount(), c.idleTimeMs()))
          .toList();
      return new JobStats(length, summary.getTotalPendingMessages(), consumers, dead);
    } catch (DataAccessException e) {
      // 아직 그룹이 없으면(앱이 처음 뜨기 전, 또는 실습 중 스트림을 지운 경우) Redis가 오류를 낸다 → 0으로 보여 준다.
      return new JobStats(length, 0, List.of(), dead);
    }
  }

  // 스트림 길이(XLEN). 키가 없으면 0.
  private long sizeOf(String key) {
    Long size = redis.opsForStream().size(key);
    return size == null ? 0 : size;
  }
}
