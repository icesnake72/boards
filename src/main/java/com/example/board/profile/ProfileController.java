package com.example.board.profile;

import com.example.board.auth.LoginUserId;
import com.example.board.profile.dto.ProfileResponse;
import com.example.board.profile.dto.ProfileUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

  // @LoginUserId가 세션에서 로그인 사용자 id를 꺼내 주입하고, 비로그인이면 401을 던진다.
  @GetMapping("/me")
  public ProfileResponse getMyProfile(@LoginUserId Long userId) {
    return profileService.getMyProfile(userId);
  }

  @PutMapping("/me")
  public ProfileResponse updateMyProfile(
      @LoginUserId Long userId,
      @Valid @RequestBody ProfileUpdateRequest request) {
    return profileService.updateMyProfile(userId, request);
  }

  @GetMapping("/{userId}")
  public ProfileResponse getProfile(@PathVariable Long userId) {
    return profileService.getProfile(userId);
  }
}
