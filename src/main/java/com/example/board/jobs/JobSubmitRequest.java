package com.example.board.jobs;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// durationSeconds 상한 10초는 min-idle(15초)보다 짧아야 한다 — 길면 실행 중인 job을 재시도 담당이 가로챈다.
public record JobSubmitRequest(
    @NotNull @Min(1) @Max(10) Integer durationSeconds,
    @NotNull @Min(0) @Max(5) Integer failTimes
) {
}
