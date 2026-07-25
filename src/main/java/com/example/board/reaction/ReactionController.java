package com.example.board.reaction;

import com.example.board.auth.CustomUserDetails;
import com.example.board.reaction.dto.ReactionRequest;
import com.example.board.reaction.dto.ReactionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 단계 13: 반응 토글. SecurityConfig의 anyRequest().authenticated()로 로그인 강제되므로
// userDetails는 항상 주입된다(비로그인은 여기 도달 전 401). user_id로 자기 반응만 조작 가능.
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReactionController {

  private final ReactionService reactionService;

  @PostMapping("/posts/{postId}/reactions")
  public ReactionResponse reactToPost(
      @PathVariable Long postId,
      @AuthenticationPrincipal CustomUserDetails userDetails,
      @Valid @RequestBody ReactionRequest request) {
    return reactionService.react(postId, userDetails.getId(), request.type());
  }

  @PostMapping("/comments/{commentId}/reactions")
  public ReactionResponse reactToComment(
      @PathVariable Long commentId,
      @AuthenticationPrincipal CustomUserDetails userDetails,
      @Valid @RequestBody ReactionRequest request) {
    return reactionService.reactToComment(commentId, userDetails.getId(), request.type());
  }
}
