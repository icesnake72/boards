package com.example.board.live;

import com.example.board.auth.CustomUserDetails;
import com.example.board.live.dto.LikeResponse;
import com.example.board.live.dto.QuestionCreateRequest;
import com.example.board.live.dto.QuestionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/live")
@RequiredArgsConstructor
public class LiveQuestionController {

  private final LiveQuestionService liveQuestionService;

  @PostMapping("/events/{code}/questions")
  @ResponseStatus(HttpStatus.CREATED)
  public QuestionResponse create(
      @PathVariable String code,
      @AuthenticationPrincipal CustomUserDetails userDetails,
      @Valid @RequestBody QuestionCreateRequest request) {
    return liveQuestionService.create(code, userDetails.getId(), request);
  }

  @PostMapping("/questions/{id}/like")
  public LikeResponse like(
      @PathVariable Long id,
      @AuthenticationPrincipal CustomUserDetails userDetails) {
    return liveQuestionService.toggleLike(id, userDetails.getId());
  }

  @PreAuthorize("@liveSecurity.canDeleteQuestion(#id, authentication.principal)")
  @DeleteMapping("/questions/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    liveQuestionService.delete(id);
  }
}
