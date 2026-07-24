package com.example.board.notification;

import com.example.board.comment.CommentRepository;
import com.example.board.post.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 단계 12 핵심 — CommentCreatedEvent를 받아 알림 대상을 결정하고 저장을 위임한다.
// CommentService는 알림 도메인을 모르고, 대상 결정(자기 자신 스킵 등)은 여기서만 안다.
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

  private final NotificationService notificationService;
  private final PostRepository postRepository;
  private final CommentRepository commentRepository;

  // AFTER_COMMIT: 댓글 저장이 롤백되면 알림도 안 만들어진다(무결성).
  // REQUIRES_NEW: AFTER_COMMIT 시점엔 원 트랜잭션이 이미 닫혀 활성 트랜잭션이 없다.
  //   새 트랜잭션 없이 save하면 커밋되지 않고 조용히 사라진다(설계 문서 §2). 이 두 줄이 이번 단계의 핵심.
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onCommentCreated(CommentCreatedEvent event) {
    Long recipientId;
    NotificationType type;
    if (event.parentCommentId() == null) {
      recipientId = postRepository.findAuthorIdById(event.postId()).orElse(null);
      type = NotificationType.COMMENT_ON_POST;
    } else {
      recipientId = commentRepository.findAuthorIdById(event.parentCommentId()).orElse(null);
      type = NotificationType.REPLY_ON_COMMENT;
    }

    // 자기 글에 자기 댓글 / 자기 댓글에 자기 답글이면 알림을 만들지 않는다.
    if (recipientId == null || recipientId.equals(event.actorId())) {
      return;
    }

    try {
      // commentId는 "이 알림을 유발한 새 댓글" — 클릭 시 그 댓글로 딥링크할 수 있게 저장한다
      // (parentCommentId가 아니라 방금 달린 댓글의 id).
      notificationService.create(recipientId, event.actorId(), type,
          event.postId(), event.commentId());
    } catch (RuntimeException e) {
      // 이미 201 응답이 나간 뒤다 — 알림 저장 실패가 댓글 응답을 되돌릴 수 없다.
      // 로그만 남기고 유실을 흡수한다(재시도/outbox는 이 단계 범위 밖).
      log.warn("알림 저장 실패: event={}", event, e);
    }
  }
}
