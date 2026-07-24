package com.example.board.post;

import com.example.board.auth.CustomUserDetails;
import com.example.board.global.exception.BusinessException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostListResponse;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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

  @GetMapping("/boards/{boardId}/posts")
  public Page<PostListResponse> getPosts(
      @PathVariable Long boardId,
      @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
      Pageable pageable) {
    return postService.getPosts(boardId, pageable);
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

  @GetMapping("/posts/{id}")
  public PostResponse getPost(@PathVariable Long id) {
    return postService.getPost(id);
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
      @Valid @RequestPart("post") PostUpdateRequest request,
      @RequestPart(value = "images", required = false) List<MultipartFile> images) {
    return postService.update(id, request, images);
  }

  @PreAuthorize("@postSecurity.isAuthor(#id, authentication.principal)")
  @DeleteMapping("/posts/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    postService.delete(id);
  }
}
