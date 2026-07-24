package com.example.board.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.board.board.Board;
import com.example.board.board.BoardRepository;
import com.example.board.comment.CommentRepository;
import com.example.board.comment.CommentService;
import com.example.board.comment.dto.CommentCreateRequest;
import com.example.board.comment.dto.CommentResponse;
import com.example.board.post.PostRepository;
import com.example.board.post.PostService;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// AFTER_COMMIT 리스너는 원 트랜잭션이 실제로 커밋돼야 실행된다.
// 따라서 이 테스트는 @Transactional(롤백) 없이 실제 커밋으로 돌리고, @AfterEach에서 손수 정리한다.
// (@Transactional을 붙이면 커밋이 없어 리스너가 안 타므로 REQUIRES_NEW 함정을 검증할 수 없다.)
@SpringBootTest
class NotificationEventListenerTest {

  @Autowired
  CommentService commentService;

  @Autowired
  PostService postService;

  @Autowired
  NotificationRepository notificationRepository;

  @Autowired
  CommentRepository commentRepository;

  @Autowired
  PostRepository postRepository;

  @Autowired
  BoardRepository boardRepository;

  @Autowired
  UserRepository userRepository;

  User postAuthor;
  User commenter;
  Board board;
  Long postId;

  @BeforeEach
  void setUp() {
    // 실제 커밋되는 테스트라 unique 제약 충돌을 피하려 매번 유일한 username/email을 쓴다.
    String suffix = String.valueOf(System.nanoTime());
    postAuthor = userRepository.save(
        new User("postAuthor" + suffix, "postauthor" + suffix + "@example.com", "enc", Role.USER));
    commenter = userRepository.save(
        new User("commenter" + suffix, "commenter" + suffix + "@example.com", "enc", Role.USER));
    board = boardRepository.save(new Board("자유게시판" + suffix, "자유롭게"));
    postId = postService.create(
        board.getId(), postAuthor.getId(), new PostCreateRequest("제목", "내용"), null).id();
  }

  // 실제 커밋되므로 이 테스트가 만든 데이터를 손수 정리한다(FK 역순).
  // users/boards는 다른 테이블(user_profiles 등)이 FK로 참조할 수 있어 지우지 않는다 —
  // username을 매번 유일하게 만들어 누적돼도 충돌하지 않는다.
  @AfterEach
  void tearDown() {
    notificationRepository.deleteAll();
    commentRepository.deleteAll();
    postRepository.deleteAll();
  }

  @Test
  void should_notifyPostAuthor_whenSomeoneCommentsOnPost() {
    CommentResponse comment =
        commentService.create(postId, commenter.getId(), new CommentCreateRequest("댓글", null));

    List<Notification> notifications = awaitNotifications(1);
    Notification notification = notifications.get(0);
    assertThat(notification.getType()).isEqualTo(NotificationType.COMMENT_ON_POST);
    assertThat(notification.getRecipient().getId()).isEqualTo(postAuthor.getId());
    assertThat(notification.getActor().getId()).isEqualTo(commenter.getId());
    assertThat(notification.getPostId()).isEqualTo(postId);
    // commentId는 방금 달린 그 댓글 — 알림 클릭 시 해당 댓글로 딥링크하기 위함
    assertThat(notification.getCommentId()).isEqualTo(comment.id());
  }

  @Test
  void should_notifyParentCommentAuthor_whenSomeoneReplies() {
    CommentResponse root =
        commentService.create(postId, postAuthor.getId(), new CommentCreateRequest("원댓글", null));
    // 게시글 작성자 == 원댓글 작성자이므로, 원댓글 알림은 자기 자신 → 스킵된다(알림 0개인 상태에서 시작).

    CommentResponse reply =
        commentService.create(postId, commenter.getId(), new CommentCreateRequest("대댓글", root.id()));

    List<Notification> notifications = awaitNotifications(1);
    Notification notification = notifications.get(0);
    assertThat(notification.getType()).isEqualTo(NotificationType.REPLY_ON_COMMENT);
    assertThat(notification.getRecipient().getId()).isEqualTo(postAuthor.getId());
    assertThat(notification.getActor().getId()).isEqualTo(commenter.getId());
    // commentId는 원댓글(root)이 아니라 방금 달린 대댓글 — 그 답글로 딥링크
    assertThat(notification.getCommentId()).isEqualTo(reply.id());
  }

  @Test
  void should_notCreateNotification_whenAuthorCommentsOnOwnPost() {
    commentService.create(postId, postAuthor.getId(), new CommentCreateRequest("내 글에 내 댓글", null));

    // 자기 자신 제외 → 알림이 생기지 않아야 한다. 리스너 처리 여유를 준 뒤에도 0인지 확인한다.
    await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
        .until(() -> notificationRepository.count() == 0);
    assertThat(notificationRepository.count()).isZero();
  }

  private List<Notification> awaitNotifications(int expectedCount) {
    await().atMost(Duration.ofSeconds(3))
        .until(() -> notificationRepository.count() == expectedCount);
    return notificationRepository.findAll();
  }
}
