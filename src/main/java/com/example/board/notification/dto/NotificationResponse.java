package com.example.board.notification.dto;

import com.example.board.notification.Notification;
import com.example.board.notification.NotificationType;
import java.time.LocalDateTime;

// 메타데이터 → 사용자 표시 문구 조립. actor.username이 바뀌면 자동 반영된다.
// from은 트랜잭션 안에서 호출된다(actor는 LAZY, @EntityGraph로 로딩됨).
public record NotificationResponse(
    Long id,
    NotificationType type,
    String message,
    String actorUsername,
    Long postId,
    Long commentId,
    boolean read,
    LocalDateTime createdAt
) {

  public static NotificationResponse from(Notification notification) {
    String actor = notification.getActor().getUsername();
    String message = switch (notification.getType()) {
      case COMMENT_ON_POST -> actor + "님이 회원님의 게시글에 댓글을 남겼습니다";
      case REPLY_ON_COMMENT -> actor + "님이 회원님의 댓글에 답글을 남겼습니다";
    };
    return new NotificationResponse(
        notification.getId(),
        notification.getType(),
        message,
        actor,
        notification.getPostId(),
        notification.getCommentId(),
        notification.isRead(),
        notification.getCreatedAt());
  }
}
