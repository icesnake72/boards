package com.example.board.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import com.example.board.notification.dto.NotificationResponse;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

// 서비스 직접 호출 테스트 — 리스너(AFTER_COMMIT)를 거치지 않으므로 @Transactional 롤백으로 격리해도 된다.
@SpringBootTest
@Transactional
class NotificationServiceTest {

  @Autowired
  NotificationService notificationService;

  @Autowired
  NotificationRepository notificationRepository;

  @Autowired
  UserRepository userRepository;

  User recipient;
  User actor;

  @BeforeEach
  void setUp() {
    recipient = userRepository.save(new User("recipient", "recipient@example.com", "enc", Role.USER));
    actor = userRepository.save(new User("actor", "actor@example.com", "enc", Role.USER));
  }

  @Test
  void should_createAndRenderMessage_forCommentOnPost() {
    notificationService.create(
        recipient.getId(), actor.getId(), NotificationType.COMMENT_ON_POST, 10L, null);

    Page<NotificationResponse> page =
        notificationService.getNotifications(recipient.getId(), PageRequest.of(0, 20));

    assertThat(page.getContent()).hasSize(1);
    NotificationResponse response = page.getContent().get(0);
    assertThat(response.type()).isEqualTo(NotificationType.COMMENT_ON_POST);
    assertThat(response.actorUsername()).isEqualTo("actor");
    assertThat(response.message()).isEqualTo("actor님이 회원님의 게시글에 댓글을 남겼습니다");
    assertThat(response.postId()).isEqualTo(10L);
    assertThat(response.read()).isFalse();
  }

  @Test
  void should_renderReplyMessage_forReplyOnComment() {
    notificationService.create(
        recipient.getId(), actor.getId(), NotificationType.REPLY_ON_COMMENT, 10L, 5L);

    NotificationResponse response =
        notificationService.getNotifications(recipient.getId(), PageRequest.of(0, 20))
            .getContent().get(0);

    assertThat(response.message()).isEqualTo("actor님이 회원님의 댓글에 답글을 남겼습니다");
    assertThat(response.commentId()).isEqualTo(5L);
  }

  @Test
  void should_onlyReturnOwnNotifications() {
    User stranger = userRepository.save(new User("stranger", "stranger@example.com", "enc", Role.USER));
    notificationService.create(
        recipient.getId(), actor.getId(), NotificationType.COMMENT_ON_POST, 10L, null);
    notificationService.create(
        stranger.getId(), actor.getId(), NotificationType.COMMENT_ON_POST, 11L, null);

    Page<NotificationResponse> page =
        notificationService.getNotifications(recipient.getId(), PageRequest.of(0, 20));

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).postId()).isEqualTo(10L);
  }

  @Test
  void should_countUnreadOnly() {
    notificationService.create(
        recipient.getId(), actor.getId(), NotificationType.COMMENT_ON_POST, 10L, null);
    notificationService.create(
        recipient.getId(), actor.getId(), NotificationType.COMMENT_ON_POST, 11L, null);

    assertThat(notificationService.unreadCount(recipient.getId())).isEqualTo(2);
  }

  @Test
  void should_markAsRead_whenOwner() {
    notificationService.create(
        recipient.getId(), actor.getId(), NotificationType.COMMENT_ON_POST, 10L, null);
    Long notificationId = notificationRepository.findAll().get(0).getId();

    notificationService.markAsRead(notificationId, recipient.getId());

    assertThat(notificationService.unreadCount(recipient.getId())).isZero();
  }

  @Test
  void should_rejectMarkAsRead_whenNotOwner() {
    User stranger = userRepository.save(new User("stranger", "stranger@example.com", "enc", Role.USER));
    notificationService.create(
        recipient.getId(), actor.getId(), NotificationType.COMMENT_ON_POST, 10L, null);
    Long notificationId = notificationRepository.findAll().get(0).getId();

    assertThatThrownBy(() -> notificationService.markAsRead(notificationId, stranger.getId()))
        .isInstanceOf(NotFoundException.class)
        .extracting(e -> ((NotFoundException) e).getErrorCode())
        .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
  }

  @Test
  void should_throwNotFound_whenNotificationMissing() {
    assertThatThrownBy(() -> notificationService.markAsRead(999L, recipient.getId()))
        .isInstanceOf(NotFoundException.class)
        .extracting(e -> ((NotFoundException) e).getErrorCode())
        .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
  }

  @Test
  void should_markAllAsRead() {
    notificationService.create(
        recipient.getId(), actor.getId(), NotificationType.COMMENT_ON_POST, 10L, null);
    notificationService.create(
        recipient.getId(), actor.getId(), NotificationType.COMMENT_ON_POST, 11L, null);

    notificationService.markAllAsRead(recipient.getId());

    assertThat(notificationService.unreadCount(recipient.getId())).isZero();
  }
}
