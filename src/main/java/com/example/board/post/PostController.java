package com.example.board.post;

import com.example.board.auth.CustomUserDetails;
import com.example.board.global.exception.BusinessException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostCursorResponse;
import com.example.board.post.dto.PostListResponse;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostUpdateRequest;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PostController {

  // 게시글당 첨부 가능한 최대 이미지 장수
  private static final int MAX_IMAGE_COUNT = 5;

  private final PostService postService;

  // 단계 16 처리에 의해 keyset 방식(/posts/cursor)이 추가됨 — 이 offset 방식은
  // 페이지 번호 점프가 필요한 화면·교육용 비교 대상으로 유지한다.
  @GetMapping("/boards/{boardId}/posts")
  public Page<PostListResponse> getPosts(
      @PathVariable Long boardId,
      @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
      Pageable pageable) {
    return postService.getPosts(boardId, pageable);
  }

  // 단계 16: keyset(cursor) 목록 — 무한스크롤용. 첫 요청은 커서 없이, 다음 요청부터
  // 직전 응답의 lastCreatedAt/lastId를 그대로 되돌려 보낸다(두 값은 항상 쌍으로).
  // size 상한은 한 번에 대량을 끌어가는 요청을 차단한다(악용/실수 모두).
  @GetMapping("/boards/{boardId}/posts/cursor")
  public PostCursorResponse getPostsByCursor(
      @PathVariable Long boardId,
      @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime lastCreatedAt,
      @RequestParam(required = false) Long lastId,
      @RequestParam(defaultValue = "20") int size) {
    if (size < 1 || size > 100) {
      throw new BusinessException(ErrorCode.INVALID_INPUT);
    }
    return postService.getPostsByCursor(boardId, lastCreatedAt, lastId, size);
  }

  // 인증 여부는 SecurityFilterChain이 판단하고, 로그인 사용자는 @AuthenticationPrincipal로 주입된다.
  // 단계 10 처리에 의해 변경 — 기존 @RequestBody(JSON) 단건에서 multipart로 전환.
  //   글 본문은 "post" 파트(application/json), 이미지는 "images" 파트(선택, 여러 장)로 받는다.
  @PostMapping(path = "/boards/{boardId}/posts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public PostResponse create(
      @PathVariable Long boardId,
      @AuthenticationPrincipal CustomUserDetails userDetails,
      @Valid @RequestPart("post") PostCreateRequest post,
      @RequestPart(value = "images", required = false) List<MultipartFile> images) {
    validateImageCount(images);
    return postService.create(boardId, userDetails.getId(), post, images);
  }

  private void validateImageCount(List<MultipartFile> images) {
    if (images != null && images.size() > MAX_IMAGE_COUNT) {
      // 장수 초과는 "파일 형식"과 다른 원인이라 별도 코드로 응답한다(클라이언트가 원인을 구분하도록)
      throw new BusinessException(ErrorCode.FILE_COUNT_EXCEEDED);
    }
  }

  // GET /posts/{id}는 permitAll이라 비로그인도 조회 가능 — 로그인 상태면 principal이 주입되고,
  // 비로그인이면 userDetails가 null이다(익명 principal은 CustomUserDetails 타입이 아님).
  // 조회수를 "남이 볼 때만" 올리기 위해 조회자 id를 서비스로 넘긴다(본인 글은 증가 제외).
  @GetMapping("/posts/{id}")
  public PostResponse getPost(
      @PathVariable Long id,
      @AuthenticationPrincipal CustomUserDetails userDetails) {
    Long viewerId = userDetails == null ? null : userDetails.getId();
    return postService.getPost(id, viewerId);
  }

  // 단계 6: 작성자만 수정/삭제 — URL 규칙으로 표현 못 하는 자원 소유권을 커스텀 보안 빈으로 검사.
  // 글이 없으면 @postSecurity.isAuthor가 404를 던지고, 작성자가 아니면 false → 403.
  // 단계 10 처리에 의해 변경 — 이미지 수정 지원 위해 multipart 전환.
  //   본문(title/content/deleteImageIds)은 "post" 파트(application/json), 신규 이미지는 "images" 파트로 받는다.
  // 기존(JSON) 방식 보존:
  //   @PutMapping("/posts/{id}")
  //   public PostResponse update(@PathVariable Long id, @Valid @RequestBody PostUpdateRequest request) {
  //     return postService.update(id, request);
  //   }
  @PreAuthorize("@postSecurity.isAuthor(#id, authentication.principal)")
  @PutMapping(path = "/posts/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public PostResponse update(
      @PathVariable Long id,
      @AuthenticationPrincipal CustomUserDetails userDetails,
      @Valid @RequestPart("post") PostUpdateRequest request,
      @RequestPart(value = "images", required = false) List<MultipartFile> images) {
    return postService.update(id, userDetails.getId(), request, images);
  }

  @PreAuthorize("@postSecurity.isAuthor(#id, authentication.principal)")
  @DeleteMapping("/posts/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    postService.delete(id);
  }
}
