package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.board.board.Board;
import com.example.board.board.BoardRepository;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostUpdateRequest;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class PostServiceTest {

  @Autowired
  PostService postService;

  @Autowired
  UserRepository userRepository;

  @Autowired
  BoardRepository boardRepository;

  User author;
  Board board;

  @BeforeEach
  void setUp() {
    author = userRepository.save(new User("author1", "author1@example.com", "encoded", Role.USER));
    board = boardRepository.save(new Board("자유게시판", "자유롭게 쓰는 곳"));
  }

  @Test
  void should_createPost_whenAuthorIsLoggedInUser() {
    PostResponse response =
        postService.create(board.getId(), author.getId(), new PostCreateRequest("제목", "내용"));

    assertThat(response.title()).isEqualTo("제목");
    assertThat(response.authorUsername()).isEqualTo("author1");
    assertThat(response.boardId()).isEqualTo(board.getId());
    assertThat(response.viewCount()).isZero();
  }

  // 단계 6: 작성자 권한 검사는 메서드 보안(@PreAuthorize)로 이동했으므로 서비스는 수정 로직만 검증한다.
  // 타인 거부(403) 검증은 SecurityIntegrationTest의 토큰 기반 통합 테스트로 옮겼다.
  @Test
  void should_updatePost() {
    PostResponse created =
        postService.create(board.getId(), author.getId(), new PostCreateRequest("제목", "내용"));

    PostResponse updated =
        postService.update(created.id(), new PostUpdateRequest("수정 제목", "수정 내용"));

    assertThat(updated.title()).isEqualTo("수정 제목");
    assertThat(updated.content()).isEqualTo("수정 내용");
  }

  @Test
  void should_deletePost() {
    PostResponse created =
        postService.create(board.getId(), author.getId(), new PostCreateRequest("제목", "내용"));

    postService.delete(created.id());

    assertThat(postService.getPosts(board.getId(),
        org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements()).isZero();
  }

  @Test
  void should_increaseViewCount_whenGetPost() {
    PostResponse created =
        postService.create(board.getId(), author.getId(), new PostCreateRequest("제목", "내용"));

    PostResponse viewed = postService.getPost(created.id());

    assertThat(viewed.viewCount()).isEqualTo(1);
  }
}
