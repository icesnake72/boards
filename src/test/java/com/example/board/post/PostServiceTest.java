package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.board.Board;
import com.example.board.board.BoardRepository;
import com.example.board.global.exception.BusinessException;
import com.example.board.global.exception.NotFoundException;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostCursorResponse;
import com.example.board.post.dto.PostListResponse;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostUpdateRequest;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
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

  @Autowired
  EntityManager em;

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

  // ── 단계 16: keyset(cursor) 페이지네이션 ─────────────────────────────────────

  private List<Long> createPosts(int count) {
    List<Long> ids = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      ids.add(postService.create(
          board.getId(), author.getId(), new PostCreateRequest("글 " + i, "내용 " + i), null).id());
    }
    return ids;
  }

  // 커서로 전체를 순회하며 만난 id를 순서대로 수집(무한 루프 방지 상한 포함)
  private List<Long> walkAllPages(int size) {
    List<Long> collected = new ArrayList<>();
    LocalDateTime lastCreatedAt = null;
    Long lastId = null;
    for (int guard = 0; guard < 100; guard++) {
      PostCursorResponse page =
          postService.getPostsByCursor(board.getId(), lastCreatedAt, lastId, size);
      page.items().stream().map(PostListResponse::id).forEach(collected::add);
      if (!page.hasNext()) {
        break;
      }
      lastCreatedAt = page.lastCreatedAt();
      lastId = page.lastId();
    }
    return collected;
  }

  @Test
  void should_returnNewestFirst_withCursor_onFirstPage() {
    List<Long> ids = createPosts(5);

    PostCursorResponse page = postService.getPostsByCursor(board.getId(), null, null, 2);

    // 최신순: 마지막에 만든 글이 맨 앞
    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).id()).isEqualTo(ids.get(4));
    assertThat(page.items().get(1).id()).isEqualTo(ids.get(3));
    assertThat(page.hasNext()).isTrue();
    // 커서 = 이번 페이지 마지막 행
    assertThat(page.lastId()).isEqualTo(ids.get(3));
    assertThat(page.lastCreatedAt()).isNotNull();
  }

  @Test
  void should_walkAllPages_withoutOverlapOrGap() {
    List<Long> ids = createPosts(5);

    List<Long> collected = walkAllPages(2);

    // 5건이 정확히 한 번씩, 최신순으로 — 페이지 경계에서 중복/누락 없음
    assertThat(collected).hasSize(5);
    assertThat(collected).doesNotHaveDuplicates();
    assertThat(collected).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    assertThat(collected).containsExactlyInAnyOrderElementsOf(ids);
  }

  @Test
  void should_setHasNextFalse_onLastPage() {
    createPosts(3);

    PostCursorResponse page = postService.getPostsByCursor(board.getId(), null, null, 5);

    assertThat(page.items()).hasSize(3);
    assertThat(page.hasNext()).isFalse();
  }

  @Test
  void should_returnEmpty_whenBoardHasNoPosts() {
    PostCursorResponse page = postService.getPostsByCursor(board.getId(), null, null, 20);

    assertThat(page.items()).isEmpty();
    assertThat(page.hasNext()).isFalse();
    assertThat(page.lastCreatedAt()).isNull();
    assertThat(page.lastId()).isNull();
  }

  // 핵심 회귀 테스트: createdAt이 전부 같아도(동률) id tie-breaker 덕에
  // 페이지 경계에서 글이 빠지거나 중복되지 않아야 한다.
  // createdAt은 @CreatedDate(updatable=false)라 JPA로는 못 바꾸므로 native로 동률을 만든다.
  @Test
  void should_notSkipOrDuplicate_whenCreatedAtTies() {
    List<Long> ids = createPosts(5);
    em.flush();
    em.createNativeQuery("update posts set created_at = :ts")
        .setParameter("ts", LocalDateTime.of(2026, 1, 1, 0, 0, 0))
        .executeUpdate();
    em.clear();

    List<Long> collected = walkAllPages(2);

    assertThat(collected).hasSize(5);
    assertThat(collected).doesNotHaveDuplicates();
    // 시각이 전부 같으므로 순서는 id 내림차순이어야 한다
    assertThat(collected).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    assertThat(collected).containsExactlyInAnyOrderElementsOf(ids);
  }

  @Test
  void should_throwNotFound_whenCursorQueryOnMissingBoard() {
    assertThatThrownBy(() -> postService.getPostsByCursor(999999L, null, null, 20))
        .isInstanceOf(NotFoundException.class);
  }

  // 단계 16 사후 개선(지연 조인) 회귀 테스트 — offset 목록이 2단계 조회(id 페이징 →
  // IN 로딩)로 바뀌어도, 순서·페이지 메타데이터·매핑이 기존 계약과 동일해야 한다.
  // IN 결과는 순서가 없으므로 "순서 복원" 로직이 빠지면 이 테스트가 잡는다.
  @Test
  void should_preserveOrderAndMetadata_onOffsetPage_withLateJoin() {
    List<Long> ids = createPosts(5);

    var pageable = org.springframework.data.domain.PageRequest.of(
        1, 2, org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Order.desc("createdAt"),
            org.springframework.data.domain.Sort.Order.desc("id")));
    Page<PostListResponse> page = postService.getPosts(board.getId(), pageable);

    // 최신순 전체 [4,3,2,1,0] 중 2페이지(index 1) → ids[2], ids[1]
    assertThat(page.getContent()).extracting(PostListResponse::id)
        .containsExactly(ids.get(2), ids.get(1));
    assertThat(page.getContent()).extracting(PostListResponse::authorUsername)
        .containsOnly("author1");
    assertThat(page.getTotalElements()).isEqualTo(5);
    assertThat(page.getTotalPages()).isEqualTo(3);
  }
}
