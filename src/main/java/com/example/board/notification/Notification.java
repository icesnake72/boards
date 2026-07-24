package com.example.board.notification;

import com.example.board.global.entity.BaseTimeEntity;
import com.example.board.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 단계 12: 인앱 알림 한 건. 완성된 문구가 아니라 메타데이터를 저장한다 —
// actor.username이 바뀌면 조회 시 렌더(NotificationResponse.from)에 자동 반영되도록.
@Entity
@Table(name = "notifications", indexes = {
    @Index(name = "idx_notifications_recipient_created", columnList = "recipient_id, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // 알림을 받는 사람. LAZY — 인가 필터(isOwnedBy)로만 쓰이고 응답에 노출하지 않는다.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "recipient_id", nullable = false)
  private User recipient;

  // 알림을 유발한 사람(댓글 작성자). LAZY — 응답 렌더 시 username을 읽으므로 트랜잭션 안에서 접근한다.
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "actor_id", nullable = false)
  private User actor;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private NotificationType type;

  // 링크용(느슨한 결합) — Post/Comment 엔티티 참조 대신 id만 저장한다.
  // 게시글/댓글이 삭제돼도 알림 자체는 남도록 FK를 강제하지 않는다.
  @Column(name = "post_id", nullable = false)
  private Long postId;

  // REPLY_ON_COMMENT에서 원댓글 id, COMMENT_ON_POST에서는 null.
  @Column(name = "comment_id")
  private Long commentId;

  // 예약어 논란을 피하려 컬럼명은 is_read. getter는 isRead()로 노출된다.
  @Column(name = "is_read", nullable = false)
  private boolean read;

  public Notification(User recipient, User actor, NotificationType type,
                      Long postId, Long commentId) {
    this.recipient = recipient;
    this.actor = actor;
    this.type = type;
    this.postId = postId;
    this.commentId = commentId;
    this.read = false;
  }

  public void markAsRead() {
    this.read = true;
  }

  // 인가 필터 — 남의 알림 id를 알아도 이 검사에서 걸린다. FK(recipient_id)만 비교해 LAZY 로딩을 유발하지 않는다.
  public boolean isOwnedBy(Long userId) {
    return recipient.getId().equals(userId);
  }
}
