package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.board.Board;
import com.example.board.board.BoardRepository;
import com.example.board.global.exception.BusinessException;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostUpdateRequest;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
        postService.create(board.getId(), author.getId(), new PostCreateRequest("제목", "내용"), null);

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
        postService.create(board.getId(), author.getId(), new PostCreateRequest("제목", "내용"), null);

    PostResponse updated = postService.update(
        created.id(), author.getId(), new PostUpdateRequest("수정 제목", "수정 내용", null), null);

    assertThat(updated.title()).isEqualTo("수정 제목");
    assertThat(updated.content()).isEqualTo("수정 내용");
  }

  @Test
  void should_addImages_whenUpdatingPostWithNewImages() {
    PostResponse created = postService.create(
        board.getId(), author.getId(), new PostCreateRequest("제목", "내용"), List.of(pngImage("a.png")));

    PostResponse updated = postService.update(
        created.id(), author.getId(), new PostUpdateRequest("제목", "내용", null), List.of(pngImage("b.png")));

    assertThat(updated.images()).hasSize(2);
    assertThat(updated.images()).extracting("originalName").containsExactly("a.png", "b.png");
  }

  @Test
  void should_deleteSpecificImage_whenDeleteImageIdsGiven() {
    PostResponse created = postService.create(
        board.getId(), author.getId(), new PostCreateRequest("제목", "내용"),
        List.of(pngImage("a.png"), pngImage("b.png")));
    Long firstImageId = created.images().get(0).id();

    PostResponse updated = postService.update(
        created.id(), author.getId(), new PostUpdateRequest("제목", "내용", List.of(firstImageId)), null);

    assertThat(updated.images()).hasSize(1);
    assertThat(updated.images().get(0).originalName()).isEqualTo("b.png");
  }

  @Test
  void should_deleteAndAddImages_atOnce() {
    PostResponse created = postService.create(
        board.getId(), author.getId(), new PostCreateRequest("제목", "내용"),
        List.of(pngImage("a.png"), pngImage("b.png")));
    Long firstImageId = created.images().get(0).id();

    PostResponse updated = postService.update(
        created.id(), author.getId(), new PostUpdateRequest("제목", "내용", List.of(firstImageId)),
        List.of(pngImage("c.png")));

    assertThat(updated.images()).hasSize(2);
    assertThat(updated.images()).extracting("originalName").containsExactlyInAnyOrder("b.png", "c.png");
  }

  @Test
  void should_ignoreForeignImageIds_whenDeleting() {
    PostResponse created = postService.create(
        board.getId(), author.getId(), new PostCreateRequest("제목", "내용"), List.of(pngImage("a.png")));

    PostResponse updated = postService.update(
        created.id(), author.getId(), new PostUpdateRequest("제목", "내용", List.of(999999L)), null);

    assertThat(updated.images()).hasSize(1);
    assertThat(updated.images().get(0).originalName()).isEqualTo("a.png");
  }

  // 보안 회귀 방지: 다른 글의 "실제로 존재하는" 이미지 id를 삭제 목록에 넣어도
  // 내 글에 없는 이미지이므로 무시되고, 그 다른 글의 이미지는 그대로 남아야 한다.
  @Test
  void should_notDeleteOtherPostsImage_whenForeignRealIdGiven() {
    PostResponse mine = postService.create(
        board.getId(), author.getId(), new PostCreateRequest("내글", "내용"), List.of(pngImage("mine.png")));
    PostResponse other = postService.create(
        board.getId(), author.getId(), new PostCreateRequest("남글", "내용"), List.of(pngImage("other.png")));
    Long foreignImageId = other.images().get(0).id();

    PostResponse updated = postService.update(
        mine.id(), author.getId(), new PostUpdateRequest("내글", "내용", List.of(foreignImageId)), null);

    // 내 글 이미지는 그대로, 남의 글 이미지도 삭제되지 않아야 한다
    assertThat(updated.images()).hasSize(1);
    assertThat(postService.getPost(other.id(), null).images()).hasSize(1);
  }

  @Test
  void should_rejectUpdate_whenFinalImageCountExceedsMax() {
    PostResponse created = postService.create(
        board.getId(), author.getId(), new PostCreateRequest("제목", "내용"),
        List.of(pngImage("a.png"), pngImage("b.png"), pngImage("c.png")));

    List<org.springframework.web.multipart.MultipartFile> newImages =
        List.of(pngImage("d.png"), pngImage("e.png"), pngImage("f.png"));

    assertThatThrownBy(() -> postService.update(
        created.id(), author.getId(), new PostUpdateRequest("제목", "내용", null), newImages))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(com.example.board.global.exception.ErrorCode.FILE_COUNT_EXCEEDED);
  }

  private MockMultipartFile pngImage(String filename) {
    return new MockMultipartFile("images", filename, MediaType.IMAGE_PNG_VALUE, new byte[] {1, 2, 3});
  }

  @Test
  void should_deletePost() {
    PostResponse created =
        postService.create(board.getId(), author.getId(), new PostCreateRequest("제목", "내용"), null);

    postService.delete(created.id());

    assertThat(postService.getPosts(board.getId(),
        org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements()).isZero();
  }

  @Test
  void should_createPostWithImages_andExposeUrlsOnGet() {
    MockMultipartFile image = new MockMultipartFile(
        "images", "photo.png", MediaType.IMAGE_PNG_VALUE, new byte[] {1, 2, 3});

    PostResponse created = postService.create(
        board.getId(), author.getId(), new PostCreateRequest("제목", "내용"), List.of(image));

    assertThat(created.images()).hasSize(1);
    assertThat(created.images().get(0).url()).startsWith("/images/");
    assertThat(created.images().get(0).originalName()).isEqualTo("photo.png");

    PostResponse fetched = postService.getPost(created.id(), null);
    assertThat(fetched.images()).hasSize(1);
    assertThat(fetched.images().get(0).url()).isEqualTo(created.images().get(0).url());
  }

  @Test
  void should_createPostWithoutImages_whenImagesNull() {
    PostResponse created = postService.create(
        board.getId(), author.getId(), new PostCreateRequest("제목", "내용"), null);

    assertThat(created.images()).isEmpty();
  }

  @Test
  void should_rejectNonImageFile() {
    MockMultipartFile textFile = new MockMultipartFile(
        "images", "note.txt", MediaType.TEXT_PLAIN_VALUE, "hello".getBytes());

    assertThatThrownBy(() -> postService.create(
        board.getId(), author.getId(), new PostCreateRequest("제목", "내용"), List.of(textFile)))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void should_exposeThumbnailUrl_onList() {
    MockMultipartFile image = new MockMultipartFile(
        "images", "thumb.png", MediaType.IMAGE_PNG_VALUE, new byte[] {9});
    postService.create(
        board.getId(), author.getId(), new PostCreateRequest("제목", "내용"), List.of(image));

    var page = postService.getPosts(board.getId(),
        org.springframework.data.domain.PageRequest.of(0, 10));

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).thumbnailUrl()).startsWith("/images/");
  }

  // 단계 10: 남이 볼 때만 조회수 증가 — 본인 글 조회는 제외, 비로그인(null)은 증가.
  @Test
  void should_increaseViewCount_whenOtherUserViews() {
    PostResponse created =
        postService.create(board.getId(), author.getId(), new PostCreateRequest("제목", "내용"), null);
    User other = userRepository.save(new User("viewer1", "viewer1@example.com", "encoded", Role.USER));

    PostResponse viewed = postService.getPost(created.id(), other.getId());

    assertThat(viewed.viewCount()).isEqualTo(1);
  }

  @Test
  void should_notIncreaseViewCount_whenAuthorViews() {
    PostResponse created =
        postService.create(board.getId(), author.getId(), new PostCreateRequest("제목", "내용"), null);

    PostResponse viewed = postService.getPost(created.id(), author.getId());

    assertThat(viewed.viewCount()).isZero();
  }

  @Test
  void should_increaseViewCount_whenAnonymousViews() {
    PostResponse created =
        postService.create(board.getId(), author.getId(), new PostCreateRequest("제목", "내용"), null);

    PostResponse viewed = postService.getPost(created.id(), null);

    assertThat(viewed.viewCount()).isEqualTo(1);
  }
}
