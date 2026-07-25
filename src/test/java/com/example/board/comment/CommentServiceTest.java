package com.example.board.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.board.Board;
import com.example.board.board.BoardRepository;
import com.example.board.comment.dto.CommentCreateRequest;
import com.example.board.comment.dto.CommentResponse;
import com.example.board.comment.dto.CommentUpdateRequest;
import com.example.board.global.exception.BusinessException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.PostService;
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
class CommentServiceTest {

  @Autowired
  CommentService commentService;

  @Autowired
  PostService postService;

  @Autowired
  UserRepository userRepository;

  @Autowired
  BoardRepository boardRepository;

  User author;
  Board board;
  Long postId;

  @BeforeEach
  void setUp() {
    author = userRepository.save(new User("author1", "author1@example.com", "encoded", Role.USER));
    board = boardRepository.save(new Board("자유게시판", "자유롭게 쓰는 곳"));
    postId = postService.create(
        board.getId(), author.getId(), new PostCreateRequest("제목", "내용"), null).id();
  }

  @Test
  void should_createRootComment() {
    CommentResponse response =
        commentService.create(postId, author.getId(), new CommentCreateRequest("첫 댓글", null));

    assertThat(response.content()).isEqualTo("첫 댓글");
    assertThat(response.authorUsername()).isEqualTo("author1");
    assertThat(response.deleted()).isFalse();
    assertThat(response.children()).isEmpty();
  }

  @Test
  void should_createReply_whenParentIsRootComment() {
    CommentResponse root =
        commentService.create(postId, author.getId(), new CommentCreateRequest("원댓글", null));

    CommentResponse reply = commentService.create(
        postId, author.getId(), new CommentCreateRequest("대댓글", root.id()));

    assertThat(reply.content()).isEqualTo("대댓글");

    Page<CommentResponse> page = commentService.getComments(postId, null, PageRequest.of(0, 10));
    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).children()).hasSize(1);
    assertThat(page.getContent().get(0).children().get(0).content()).isEqualTo("대댓글");
  }

  @Test
  void should_rejectReply_whenParentIsAlreadyReply() {
    CommentResponse root =
        commentService.create(postId, author.getId(), new CommentCreateRequest("원댓글", null));
    CommentResponse reply = commentService.create(
        postId, author.getId(), new CommentCreateRequest("대댓글", root.id()));

    assertThatThrownBy(() -> commentService.create(
        postId, author.getId(), new CommentCreateRequest("대대댓글", reply.id())))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.CANNOT_REPLY_TO_REPLY);
  }

  @Test
  void should_rejectReply_whenParentIsDeleted() {
    CommentResponse root =
        commentService.create(postId, author.getId(), new CommentCreateRequest("원댓글", null));
    commentService.delete(root.id());

    assertThatThrownBy(() -> commentService.create(
        postId, author.getId(), new CommentCreateRequest("대댓글", root.id())))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.CANNOT_REPLY_TO_DELETED);
  }

  @Test
  void should_rejectReply_whenParentBelongsToAnotherPost() {
    Long otherPostId = postService.create(
        board.getId(), author.getId(), new PostCreateRequest("다른글", "내용"), null).id();
    CommentResponse rootOnOther =
        commentService.create(otherPostId, author.getId(), new CommentCreateRequest("남글 댓글", null));

    assertThatThrownBy(() -> commentService.create(
        postId, author.getId(), new CommentCreateRequest("잘못된 대댓글", rootOnOther.id())))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMMENT_POST_MISMATCH);
  }

  @Test
  void should_softDeleteAndMaskContent_whenDeleted() {
    CommentResponse root =
        commentService.create(postId, author.getId(), new CommentCreateRequest("지울 댓글", null));

    commentService.delete(root.id());

    CommentResponse fetched =
        commentService.getComments(postId, null, PageRequest.of(0, 10)).getContent().get(0);
    assertThat(fetched.deleted()).isTrue();
    assertThat(fetched.content()).isEqualTo("삭제된 댓글입니다");
  }

  @Test
  void should_keepReply_whenParentRootIsSoftDeleted() {
    CommentResponse root =
        commentService.create(postId, author.getId(), new CommentCreateRequest("원댓글", null));
    commentService.create(postId, author.getId(), new CommentCreateRequest("대댓글", root.id()));

    commentService.delete(root.id());

    CommentResponse fetched =
        commentService.getComments(postId, null, PageRequest.of(0, 10)).getContent().get(0);
    assertThat(fetched.deleted()).isTrue();
    assertThat(fetched.content()).isEqualTo("삭제된 댓글입니다");
    assertThat(fetched.children()).hasSize(1);
    assertThat(fetched.children().get(0).content()).isEqualTo("대댓글");
    assertThat(fetched.children().get(0).deleted()).isFalse();
  }

  @Test
  void should_pageOnlyRootComments_withRepliesNested() {
    CommentResponse root1 =
        commentService.create(postId, author.getId(), new CommentCreateRequest("원댓글1", null));
    commentService.create(postId, author.getId(), new CommentCreateRequest("원댓글2", null));
    commentService.create(postId, author.getId(), new CommentCreateRequest("대댓글1-1", root1.id()));
    commentService.create(postId, author.getId(), new CommentCreateRequest("대댓글1-2", root1.id()));

    Page<CommentResponse> page = commentService.getComments(postId, null, PageRequest.of(0, 10));

    assertThat(page.getTotalElements()).isEqualTo(2);
    CommentResponse fetchedRoot1 = page.getContent().stream()
        .filter(c -> c.content().equals("원댓글1"))
        .findFirst()
        .orElseThrow();
    assertThat(fetchedRoot1.children()).hasSize(2);
    assertThat(fetchedRoot1.children()).extracting(CommentResponse::content)
        .containsExactly("대댓글1-1", "대댓글1-2");
  }

  // 서로 다른 작성자의 대댓글도 각자 authorUsername이 정확히 매핑되어야 한다
  // (대댓글 author는 default_batch_fetch_size로 배치 로딩된다 — 작성자가 다양해도 N+1 완화).
  @Test
  void should_mapDistinctAuthors_forReplies() {
    User replier = userRepository.save(
        new User("replier1", "replier1@example.com", "encoded", Role.USER));
    CommentResponse root =
        commentService.create(postId, author.getId(), new CommentCreateRequest("원댓글", null));
    commentService.create(postId, author.getId(), new CommentCreateRequest("author의 대댓글", root.id()));
    commentService.create(postId, replier.getId(), new CommentCreateRequest("replier의 대댓글", root.id()));

    CommentResponse fetchedRoot =
        commentService.getComments(postId, null, PageRequest.of(0, 10)).getContent().get(0);

    assertThat(fetchedRoot.children()).extracting(CommentResponse::authorUsername)
        .containsExactly("author1", "replier1");
  }

  @Test
  void should_updateContent() {
    CommentResponse root =
        commentService.create(postId, author.getId(), new CommentCreateRequest("원본", null));

    CommentResponse updated =
        commentService.update(root.id(), author.getId(), new CommentUpdateRequest("수정됨"));

    assertThat(updated.content()).isEqualTo("수정됨");
  }

  @Test
  void should_rejectUpdate_whenCommentIsDeleted() {
    CommentResponse root =
        commentService.create(postId, author.getId(), new CommentCreateRequest("원본", null));
    commentService.delete(root.id());

    assertThatThrownBy(() ->
        commentService.update(root.id(), author.getId(), new CommentUpdateRequest("수정 시도")))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.CANNOT_EDIT_DELETED);
  }
}
