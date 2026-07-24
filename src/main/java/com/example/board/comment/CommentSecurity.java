package com.example.board.comment;

import com.example.board.auth.CustomUserDetails;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 단계 11 — '작성자만 수정/삭제'를 메서드 보안 + 커스텀 빈으로(PostSecurity와 동일 패턴).
// @PreAuthorize("@commentSecurity.isAuthor(#id, authentication.principal)")에서 SpEL로 호출된다.
@Component("commentSecurity")
@RequiredArgsConstructor
public class CommentSecurity {

  private final CommentRepository commentRepository;

  // 댓글이 없으면 404를 보존하기 위해 여기서 NotFoundException을 던진다(인가 이전에 존재 여부 확정).
  // 존재하지만 작성자가 아니면 false → AccessDeniedException → 403(ACCESS_DENIED).
  public boolean isAuthor(Long commentId, CustomUserDetails user) {
    Long authorId = commentRepository.findAuthorIdById(commentId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.COMMENT_NOT_FOUND));
    return authorId.equals(user.getId());
  }
}
