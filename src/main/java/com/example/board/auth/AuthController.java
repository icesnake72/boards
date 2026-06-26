package com.example.board.auth;

import com.example.board.auth.dto.LoginRequest;
import com.example.board.auth.dto.RefreshTokenRequest;
import com.example.board.auth.dto.SignupRequest;
import com.example.board.auth.dto.TokenResponse;
import com.example.board.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  @PostMapping("/signup")
  @ResponseStatus(HttpStatus.CREATED)
  public UserResponse signup(@Valid @RequestBody SignupRequest request) {
    return authService.signup(request);
  }

  // JWT 로그인 흐름 (강의 포인트):
  // 1. 서버는 username/password를 검증하고 access token(stateless JWT) + refresh token(opaque, DB 저장)을 발급한다
  // 2. access token으로는 매 요청을 인증하고(stateless), refresh token으로는 만료된 access token만 재발급한다(stateful)
  // 3. 이후 클라이언트는 매 요청 Authorization: Bearer <accessToken> 헤더로 자신을 증명한다
  @PostMapping("/login")
  public TokenResponse login(@Valid @RequestBody LoginRequest request) {
    return authService.login(request);
  }

  // access token이 만료되면 refresh token으로 새 access token만 재발급한다(refresh token은 그대로).
  // refresh token 자체가 자격증명이라 인증 헤더 없이 호출된다(/auth/** 공개).
  @PostMapping("/reissue")
  public TokenResponse reissue(@Valid @RequestBody RefreshTokenRequest request) {
    return authService.reissue(request.refreshToken());
  }

  // 이제 stateless가 아니다 — 서버 측 refresh token을 DB에서 지운다.
  // access token은 여전히 stateless라 만료 전까지 유효하다(강제 무효화는 토큰 블랙리스트 필요 — 후속 주제).
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@Valid @RequestBody RefreshTokenRequest request) {
    authService.logout(request.refreshToken());
  }
}
