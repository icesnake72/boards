package com.example.board.profile;

import com.example.board.auth.SessionConst;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.UnauthorizedException;
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
import org.springframework.web.bind.annotation.SessionAttribute;

@RestController
@RequestMapping("/api/v1/profiles")
@RequiredArgsConstructor
public class ProfileController {

  private final ProfileService profileService;

  // @SessionAttribute: 세션에서 loginUserId를 꺼낸다.
  // required = false → 비로그인(세션 없음)이면 null이 들어오고, requireLogin()에서 401 처리
  @GetMapping("/me")
  public ProfileResponse getMyProfile(
      @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId) {
    return profileService.getMyProfile(requireLogin(loginUserId));
  }

  @PutMapping("/me")
  public ProfileResponse updateMyProfile(
      @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId,
      @Valid @RequestBody ProfileUpdateRequest request) {
    return profileService.updateMyProfile(requireLogin(loginUserId), request);
  }

  @GetMapping("/{userId}")
  public ProfileResponse getProfile(@PathVariable Long userId) {
    return profileService.getProfile(userId);
  }

  private Long requireLogin(Long loginUserId) {
    if (loginUserId == null) {
      throw new UnauthorizedException(ErrorCode.LOGIN_REQUIRED);
    }
    return loginUserId;
  }
}
