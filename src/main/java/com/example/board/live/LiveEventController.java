package com.example.board.live;

import com.example.board.auth.CustomUserDetails;
import com.example.board.live.dto.LiveEventCreateRequest;
import com.example.board.live.dto.LiveEventResponse;
import com.example.board.live.dto.LiveSnapshotResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/live")
@RequiredArgsConstructor
public class LiveEventController {

  private final LiveEventService liveEventService;

  @PostMapping("/events")
  @ResponseStatus(HttpStatus.CREATED)
  public LiveEventResponse create(
      @AuthenticationPrincipal CustomUserDetails userDetails,
      @Valid @RequestBody LiveEventCreateRequest request) {
    return liveEventService.create(userDetails.getId(), request);
  }

  // "/events/mine"(리터럴)이 "/events/{code}"(패턴)보다 우선 매칭된다.
  @GetMapping("/events/mine")
  public List<LiveEventResponse> mine(@AuthenticationPrincipal CustomUserDetails userDetails) {
    return liveEventService.getMine(userDetails.getId());
  }

  @GetMapping("/events/{code}")
  public LiveSnapshotResponse snapshot(
      @PathVariable String code,
      @AuthenticationPrincipal CustomUserDetails userDetails) {
    return liveEventService.getSnapshot(code, userDetails.getId());
  }

  @PreAuthorize("@liveSecurity.isHost(#code, authentication.principal)")
  @PostMapping("/events/{code}/close")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void close(@PathVariable String code) {
    liveEventService.close(code);
  }

  // SseEmitter를 반환하면 Spring MVC가 응답을 닫지 않고 비동기 모드로 열어 둔다(Content-Type: text/event-stream).
  // X-Accel-Buffering: no — nginx에게 "이 응답은 모으지 말고 바로 흘려보내라"는 신호.
  @GetMapping("/events/{code}/stream")
  public SseEmitter stream(@PathVariable String code, HttpServletResponse response) {
    response.setHeader("X-Accel-Buffering", "no");
    return liveEventService.subscribe(code);
  }
}
