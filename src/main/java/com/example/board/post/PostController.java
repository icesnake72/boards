package com.example.board.post;

import com.example.board.auth.CustomUserDetails;
import com.example.board.post.dto.PostCreateRequest;
import com.example.board.post.dto.PostListResponse;
import com.example.board.post.dto.PostResponse;
import com.example.board.post.dto.PostUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PostController {

  private final PostService postService;

  @GetMapping("/boards/{boardId}/posts")
  public Page<PostListResponse> getPosts(
      @PathVariable Long boardId,
      @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
      Pageable pageable) {
    return postService.getPosts(boardId, pageable);
  }

  // 인증 여부는 SecurityFilterChain이 판단하고, 로그인 사용자는 @AuthenticationPrincipal로 주입된다.
  @PostMapping("/boards/{boardId}/posts")
  @ResponseStatus(HttpStatus.CREATED)
  public PostResponse create(
      @PathVariable Long boardId,
      @AuthenticationPrincipal CustomUserDetails userDetails,
      @Valid @RequestBody PostCreateRequest request) {
    return postService.create(boardId, userDetails.getId(), request);
  }

  @GetMapping("/posts/{id}")
  public PostResponse getPost(@PathVariable Long id) {
    return postService.getPost(id);
  }

  // 단계 6: 작성자만 수정/삭제 — URL 규칙으로 표현 못 하는 자원 소유권을 커스텀 보안 빈으로 검사.
  // 글이 없으면 @postSecurity.isAuthor가 404를 던지고, 작성자가 아니면 false → 403.
  @PreAuthorize("@postSecurity.isAuthor(#id, authentication.principal)")
  @PutMapping("/posts/{id}")
  public PostResponse update(
      @PathVariable Long id,
      @Valid @RequestBody PostUpdateRequest request) {
    return postService.update(id, request);
  }

  @PreAuthorize("@postSecurity.isAuthor(#id, authentication.principal)")
  @DeleteMapping("/posts/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    postService.delete(id);
  }
}
