package com.example.board.jobs;

// 단계 19: QUEUED → RUNNING → DONE, 실패면 RETRY_WAIT(PEL에서 대기) → … → 한도 초과 시 FAILED
public enum JobStatus {
  QUEUED,
  RUNNING,
  RETRY_WAIT,
  DONE,
  FAILED
}
