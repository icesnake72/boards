package com.example.board.jobs;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 단계 19: app.jobs.* 설정 — BoardApplication의 @ConfigurationPropertiesScan이 빈으로 등록한다.
@ConfigurationProperties(prefix = "app.jobs")
public record JobProperties(
    boolean enabled,
    String stream,
    String group,
    String deadStream,
    int workers,
    int maxAttempts,
    long minIdleMs,
    long reclaimIntervalMs,
    long statusTtlHours,
    long workUnitMs
) {
}
