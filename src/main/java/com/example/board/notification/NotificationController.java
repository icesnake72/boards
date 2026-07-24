package com.example.board.notification;

import com.example.board.auth.CustomUserDetails;
import com.example.board.notification.dto.NotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 모든 엔드포인트는 인증 필요 — SecurityConfig의 anyRequest().authenticated()가 커버한다.
// 본인 알림만 접근: recipient 필터(목록/카운트)와 소유 검증(개별 읽음)은 서비스가 담당한다.
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

  private final NotificationService notificationService;

  @GetMapping
  public Page<NotificationResponse> list(
      @AuthenticationPrincipal CustomUserDetails userDetails,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
      Pageable pageable) {
    return notificationService.getNotifications(userDetails.getId(), pageable);
  }

  @GetMapping("/unread-count")
  public UnreadCountResponse unreadCount(
      @AuthenticationPrincipal CustomUserDetails userDetails) {
    return new UnreadCountResponse(notificationService.unreadCount(userDetails.getId()));
  }

  @PatchMapping("/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markAsRead(
      @PathVariable Long id,
      @AuthenticationPrincipal CustomUserDetails userDetails) {
    notificationService.markAsRead(id, userDetails.getId());
  }

  @PatchMapping("/read-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void markAllAsRead(@AuthenticationPrincipal CustomUserDetails userDetails) {
    notificationService.markAllAsRead(userDetails.getId());
  }

  public record UnreadCountResponse(long count) {}
}
