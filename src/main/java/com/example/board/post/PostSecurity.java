package com.example.board.post;

import com.example.board.auth.CustomUserDetails;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 강의 단계 6 — URL 규칙으로 표현 못 하는 '작성자만'을 메서드 보안 + 커스텀 빈으로.
// @PreAuthorize("@postSecurity.isAuthor(#id, authentication.principal)")에서 SpEL로 호출된다.
@Component("postSecurity")
@RequiredArgsConstructor
public class PostSecurity {

  private final PostRepository postRepository;

  // 글이 없으면 404를 보존하기 위해 여기서 NotFoundException을 던진다(인가 이전에 존재 여부 확정).
  // 존재하지만 작성자가 아니면 false → AccessDeniedException → 403(ACCESS_DENIED).
  public boolean isAuthor(Long postId, CustomUserDetails user) {
    Long authorId = postRepository.findAuthorIdById(postId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.POST_NOT_FOUND));
    return authorId.equals(user.getId());
  }
}
