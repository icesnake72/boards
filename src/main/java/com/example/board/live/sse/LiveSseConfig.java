package com.example.board.live.sse;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 단계 18: LiveSseRegistry.heartbeat()의 @Scheduled를 동작시킨다(board 최초의 스케줄 작업).
@Configuration
@EnableScheduling
public class LiveSseConfig {
}
