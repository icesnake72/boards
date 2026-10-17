package com.example.board.jobs;

import java.util.List;

// 단계 19: JobStats — 큐 상황판(JobMonitor)의 결과를 담는 "응답 상자". 로직은 없고 값만 담는다(record).
//
// GET /api/v1/jobs/stats 응답 예
//   {"streamLength":7,"pending":0,
//    "consumers":[{"name":"worker-1","pending":0,"idleMs":104}, …],
//    "deadLetters":1}
//
// 필드 뜻
//   streamLength : 큐에 지금까지 들어온 job 수(누적 — 완료돼도 줄지 않는다)
//   pending      : 지금 처리 중(완료 도장 ACK 전)인 job 수 — 실제 "밀려 있는 일"의 양은 이 값으로 본다
//   consumers    : 작업자별 현황 목록(worker-1, worker-2, reclaimer)
//   deadLetters  : 3번 실패해서 실패 보관함(jobs:dead)으로 간 job 수
public record JobStats(long streamLength, long pending, List<ConsumerStat> consumers, long deadLetters) {

  // 작업자 한 명의 현황
  //   name    : 작업자 이름(worker-1, worker-2, reclaimer)
  //   pending : 이 작업자가 들고 있는(아직 ACK 안 한) job 수
  //   idleMs  : 이 작업자가 마지막으로 활동한 뒤 지난 시간(ms) — 계속 커지면 일이 없거나 멈춘 것
  public record ConsumerStat(String name, long pending, long idleMs) {
  }
}
