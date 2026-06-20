package com.example.board.profile;

import com.example.board.auth.CustomUserDetails;
import com.example.board.profile.dto.ProfileResponse;
import com.example.board.profile.dto.ProfileUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles")
@RequiredArgsConstructor
public class ProfileController {

  private final ProfileService profileService;

  // @AuthenticationPrincipal이 SecurityContext의 인증 정보(CustomUserDetails)를 주입한다.
  // 비로그인이면 SecurityFilterChain이 먼저 401을 응답하므로 여기까지 오지 않는다.
  @GetMapping("/me")
  public ProfileResponse getMyProfile(@AuthenticationPrincipal CustomUserDetails userDetails) {
    return profileService.getMyProfile(userDetails.getId());
  }

  @PutMapping("/me")
  public ProfileResponse updateMyProfile(
      @AuthenticationPrincipal CustomUserDetails userDetails,
      @Valid @RequestBody ProfileUpdateRequest request) {
    return profileService.updateMyProfile(userDetails.getId(), request);
  }

  @GetMapping("/{userId}")
  public ProfileResponse getProfile(@PathVariable Long userId) {
    return profileService.getProfile(userId);
  }
}
