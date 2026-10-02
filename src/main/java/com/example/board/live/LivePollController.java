package com.example.board.live;

import com.example.board.auth.CustomUserDetails;
import com.example.board.live.dto.PollCreateRequest;
import com.example.board.live.dto.PollResponse;
import com.example.board.live.dto.VoteRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/live")
@RequiredArgsConstructor
public class LivePollController {

  private final LivePollService livePollService;

  @PreAuthorize("@liveSecurity.isHost(#code, authentication.principal)")
  @PostMapping("/events/{code}/polls")
  @ResponseStatus(HttpStatus.CREATED)
  public PollResponse create(
      @PathVariable String code,
      @Valid @RequestBody PollCreateRequest request) {
    return livePollService.create(code, request);
  }

  @PostMapping("/polls/{id}/votes")
  public PollResponse vote(
      @PathVariable Long id,
      @AuthenticationPrincipal CustomUserDetails userDetails,
      @Valid @RequestBody VoteRequest request) {
    return livePollService.vote(id, request.optionId(), userDetails.getId());
  }

  @PreAuthorize("@liveSecurity.isPollHost(#id, authentication.principal)")
  @PostMapping("/polls/{id}/close")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void close(@PathVariable Long id) {
    livePollService.close(id);
  }
}
