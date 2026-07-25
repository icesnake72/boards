package com.example.board.reaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.board.Board;
import com.example.board.board.BoardRepository;
import com.example.board.comment.CommentService;
import com.example.board.comment.dto.CommentCreateRequest;
import com.example.board.comment.dto.CommentResponse;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import com.example.board.post.PostService;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.reaction.dto.ReactionResponse;
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

@SpringBootTest
@Transactional
class ReactionServiceTest {

  @Autowired
  ReactionService reactionService;

  @Autowired
  PostService postService;

  @Autowired
  CommentService commentService;

  @Autowired
  UserRepository userRepository;

  @Autowired
  BoardRepository boardRepository;

  User author;
  User viewer;
  Board board;
  Long postId;

  @BeforeEach
  void setUp() {
    author = userRepository.save(new User("author1", "author1@example.com", "encoded", Role.USER));
    viewer = userRepository.save(new User("viewer1", "viewer1@example.com", "encoded", Role.USER));
    board = boardRepository.save(new Board("자유게시판", "자유롭게 쓰는 곳"));
    postId = postService.create(
        board.getId(), author.getId(), new PostCreateRequest("제목", "내용"), null).id();
  }

  @Test
  void should_createLike_whenFirstReaction() {
    ReactionResponse response = reactionService.react(postId, viewer.getId(), ReactionType.LIKE);

    assertThat(response.likeCount()).isEqualTo(1);
    assertThat(response.dislikeCount()).isZero();
    assertThat(response.myReaction()).isEqualTo(ReactionType.LIKE);
  }

  @Test
  void should_cancelLike_whenSameReactionRepeated() {
    reactionService.react(postId, viewer.getId(), ReactionType.LIKE);

    ReactionResponse response = reactionService.react(postId, viewer.getId(), ReactionType.LIKE);

    assertThat(response.likeCount()).isZero();
    assertThat(response.myReaction()).isNull();
  }

  @Test
  void should_switchToDislike_whenOppositeReaction() {
    reactionService.react(postId, viewer.getId(), ReactionType.LIKE);

    ReactionResponse response = reactionService.react(postId, viewer.getId(), ReactionType.DISLIKE);

    assertThat(response.likeCount()).isZero();
    assertThat(response.dislikeCount()).isEqualTo(1);
    assertThat(response.myReaction()).isEqualTo(ReactionType.DISLIKE);
  }

  @Test
  void should_accumulateLikes_fromDifferentUsers() {
    reactionService.react(postId, author.getId(), ReactionType.LIKE);

    ReactionResponse response = reactionService.react(postId, viewer.getId(), ReactionType.LIKE);

    assertThat(response.likeCount()).isEqualTo(2);
  }

  @Test
  void should_allowSelfReaction_onOwnPost() {
    ReactionResponse response = reactionService.react(postId, author.getId(), ReactionType.LIKE);

    assertThat(response.likeCount()).isEqualTo(1);
    assertThat(response.myReaction()).isEqualTo(ReactionType.LIKE);
  }

  @Test
  void should_throwNotFound_whenPostMissing() {
    assertThatThrownBy(() -> reactionService.react(999999L, viewer.getId(), ReactionType.LIKE))
        .isInstanceOf(NotFoundException.class)
        .extracting(e -> ((NotFoundException) e).getErrorCode())
        .isEqualTo(ErrorCode.POST_NOT_FOUND);
  }

  @Test
  void should_exposePostReaction_onGetPost() {
    reactionService.react(postId, author.getId(), ReactionType.LIKE);
    reactionService.react(postId, viewer.getId(), ReactionType.DISLIKE);

    var response = postService.getPost(postId, viewer.getId());

    assertThat(response.likeCount()).isEqualTo(1);
    assertThat(response.dislikeCount()).isEqualTo(1);
    assertThat(response.myReaction()).isEqualTo(ReactionType.DISLIKE);
  }

  @Test
  void should_returnNullMyReaction_whenAnonymousGetPost() {
    reactionService.react(postId, viewer.getId(), ReactionType.LIKE);

    var response = postService.getPost(postId, null);

    assertThat(response.likeCount()).isEqualTo(1);
    assertThat(response.myReaction()).isNull();
  }

  @Test
  void should_toggleCommentReaction() {
    Long commentId =
        commentService.create(postId, author.getId(), new CommentCreateRequest("댓글", null)).id();

    ReactionResponse liked =
        reactionService.reactToComment(commentId, viewer.getId(), ReactionType.LIKE);
    assertThat(liked.likeCount()).isEqualTo(1);
    assertThat(liked.myReaction()).isEqualTo(ReactionType.LIKE);

    ReactionResponse switched =
        reactionService.reactToComment(commentId, viewer.getId(), ReactionType.DISLIKE);
    assertThat(switched.likeCount()).isZero();
    assertThat(switched.dislikeCount()).isEqualTo(1);
    assertThat(switched.myReaction()).isEqualTo(ReactionType.DISLIKE);
  }

  @Test
  void should_throwNotFound_whenCommentMissing() {
    assertThatThrownBy(
        () -> reactionService.reactToComment(999999L, viewer.getId(), ReactionType.LIKE))
        .isInstanceOf(NotFoundException.class)
        .extracting(e -> ((NotFoundException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMENT_NOT_FOUND);
  }

  // 댓글 목록 반응 집계(N+1 회피) — 최상위/대댓글, 여러 사용자 조합에서 각 댓글의 카운트와 myReaction이 정확해야 한다.
  @Test
  void should_aggregateCommentReactions_forListWithoutNPlusOne() {
    User third = userRepository.save(new User("third1", "third1@example.com", "encoded", Role.USER));

    Long root1 =
        commentService.create(postId, author.getId(), new CommentCreateRequest("원댓글1", null)).id();
    Long root2 =
        commentService.create(postId, author.getId(), new CommentCreateRequest("원댓글2", null)).id();
    Long reply1 =
        commentService.create(postId, author.getId(), new CommentCreateRequest("대댓글1", root1)).id();

    // root1: LIKE 2 (viewer, third), root2: DISLIKE 1 (viewer), reply1: LIKE 1 (author)
    reactionService.reactToComment(root1, viewer.getId(), ReactionType.LIKE);
    reactionService.reactToComment(root1, third.getId(), ReactionType.LIKE);
    reactionService.reactToComment(root2, viewer.getId(), ReactionType.DISLIKE);
    reactionService.reactToComment(reply1, author.getId(), ReactionType.LIKE);

    Page<CommentResponse> page =
        commentService.getComments(postId, viewer.getId(), PageRequest.of(0, 10));

    CommentResponse c1 = find(page, "원댓글1");
    CommentResponse c2 = find(page, "원댓글2");

    assertThat(c1.likeCount()).isEqualTo(2);
    assertThat(c1.myReaction()).isEqualTo(ReactionType.LIKE);
    assertThat(c1.children()).hasSize(1);
    assertThat(c1.children().get(0).likeCount()).isEqualTo(1);
    assertThat(c1.children().get(0).myReaction()).isNull(); // viewer는 reply1에 반응 안 함

    assertThat(c2.dislikeCount()).isEqualTo(1);
    assertThat(c2.myReaction()).isEqualTo(ReactionType.DISLIKE);
  }

  @Test
  void should_returnNullMyReaction_whenAnonymousGetComments() {
    Long commentId =
        commentService.create(postId, author.getId(), new CommentCreateRequest("댓글", null)).id();
    reactionService.reactToComment(commentId, viewer.getId(), ReactionType.LIKE);

    CommentResponse fetched =
        commentService.getComments(postId, null, PageRequest.of(0, 10)).getContent().get(0);

    assertThat(fetched.likeCount()).isEqualTo(1);
    assertThat(fetched.myReaction()).isNull();
  }

  private CommentResponse find(Page<CommentResponse> page, String content) {
    return page.getContent().stream()
        .filter(c -> c.content().equals(content))
        .findFirst()
        .orElseThrow();
  }
}
