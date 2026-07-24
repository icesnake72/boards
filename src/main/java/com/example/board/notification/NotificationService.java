package com.example.board.notification;

import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import com.example.board.notification.dto.NotificationResponse;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationService {

  private final NotificationRepository notificationRepository;
  private final UserRepository userRepository;

  // 리스너(REQUIRES_NEW)에서 호출된다. 대상 결정은 리스너의 책임, 여기선 순수 저장만(SRP).
  @Transactional
  public void create(Long recipientId, Long actorId, NotificationType type,
                     Long postId, Long commentId) {
    User recipient = userRepository.findById(recipientId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
    User actor = userRepository.findById(actorId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
    notificationRepository.save(new Notification(recipient, actor, type, postId, commentId));
  }

  // 본인 것만 — recipient 필터가 강제되어 남의 알림은 결과에 안 실린다.
  @Transactional(readOnly = true)
  public Page<NotificationResponse> getNotifications(Long recipientId, Pageable pageable) {
    return notificationRepository.findByRecipientId(recipientId, pageable)
        .map(NotificationResponse::from);
  }

  @Transactional(readOnly = true)
  public long unreadCount(Long recipientId) {
    return notificationRepository.countByRecipientIdAndReadIsFalse(recipientId);
  }

  // 개별 읽음 — 소유 검증 필수. 남의 알림 id를 알아도 404로 존재를 감춘다(열거 방어).
  @Transactional
  public void markAsRead(Long id, Long loginUserId) {
    Notification notification = notificationRepository.findById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCode.NOTIFICATION_NOT_FOUND));
    if (!notification.isOwnedBy(loginUserId)) {
      throw new NotFoundException(ErrorCode.NOTIFICATION_NOT_FOUND);
    }
    notification.markAsRead();
  }

  // 전체 읽음 — 자기 것만 벌크 UPDATE(개별 로드 없이 한 쿼리).
  @Transactional
  public void markAllAsRead(Long loginUserId) {
    notificationRepository.markAllAsRead(loginUserId);
  }
}
