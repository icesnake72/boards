package com.example.board.comment;

import com.example.board.auth.CustomUserDetails;
import com.example.board.comment.dto.CommentCreateRequest;
import com.example.board.comment.dto.CommentResponse;
import com.example.board.comment.dto.CommentUpdateRequest;
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
public class CommentController {

  private final CommentService commentService;

  // 작성은 인증 필요(SecurityConfig의 anyRequest().authenticated()로 강제).
  @PostMapping("/posts/{postId}/comments")
  @ResponseStatus(HttpStatus.CREATED)
  public CommentResponse create(
      @PathVariable Long postId,
      @AuthenticationPrincipal CustomUserDetails userDetails,
      @Valid @RequestBody CommentCreateRequest request) {
    return commentService.create(postId, userDetails.getId(), request);
  }

  // 조회는 공개 — GET /api/v1/posts/** permitAll 규칙에 이미 포함된다(SecurityConfig).
  @GetMapping("/posts/{postId}/comments")
  public Page<CommentResponse> getComments(
      @PathVariable Long postId,
      @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
      Pageable pageable) {
    return commentService.getComments(postId, pageable);
  }

  // 작성자만 수정 — 댓글이 없으면 @commentSecurity가 404, 작성자가 아니면 false → 403.
  @PreAuthorize("@commentSecurity.isAuthor(#id, authentication.principal)")
  @PutMapping("/comments/{id}")
  public CommentResponse update(
      @PathVariable Long id,
      @Valid @RequestBody CommentUpdateRequest request) {
    return commentService.update(id, request);
  }

  @PreAuthorize("@commentSecurity.isAuthor(#id, authentication.principal)")
  @DeleteMapping("/comments/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    commentService.delete(id);
  }
}
