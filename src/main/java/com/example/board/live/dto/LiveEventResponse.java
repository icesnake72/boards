package com.example.board.live.dto;

import com.example.board.live.LiveEvent;
import com.example.board.live.LiveStatus;
import java.time.LocalDateTime;

// host: 조회자가 진행자인지 — 프론트가 진행자 전용 버튼을 그릴지 판단한다(실제 인가는 서버의 @liveSecurity).
public record LiveEventResponse(
    Long id,
    String code,
    String title,
    LiveStatus status,
    String hostUsername,
    boolean host,
    LocalDateTime createdAt
) {

  public static LiveEventResponse from(LiveEvent event, Long viewerId) {
    return new LiveEventResponse(
        event.getId(),
        event.getCode(),
        event.getTitle(),
        event.getStatus(),
        event.getHost().getUsername(),
        event.isHostedBy(viewerId),
        event.getCreatedAt());
  }
}
