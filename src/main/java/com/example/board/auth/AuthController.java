package com.example.board.auth;

import com.example.board.auth.dto.LoginRequest;
import com.example.board.auth.dto.SignupRequest;
import com.example.board.auth.dto.TokenPair;
import com.example.board.auth.dto.TokenResponse;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.UnauthorizedException;
import com.example.board.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;
  private final RefreshCookieFactory refreshCookieFactory;

  @PostMapping("/signup")
  @ResponseStatus(HttpStatus.CREATED)
  public UserResponse signup(@Valid @RequestBody SignupRequest request) {
    return authService.signup(request);
  }

  // 단계 5 핵심: access token은 본문(JSON)으로, refresh token은 httpOnly 쿠키(Set-Cookie)로 내려준다.
  // refresh token을 본문에 두지 않아 JS가 접근할 수 없고(XSS 탈취 방어), 브라우저가 자동으로 동봉해 보낸다.
  @PostMapping("/login")
  public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
    TokenPair tokens = authService.login(request);
    ResponseCookie refreshCookie =
        refreshCookieFactory.create(tokens.refreshToken(), tokens.refreshTokenValiditySeconds());
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
        .body(TokenResponse.bearer(tokens.accessToken(), tokens.accessTokenValiditySeconds()));
  }

  // 단계 5: 더 이상 본문(RefreshTokenRequest)으로 받지 않고 httpOnly 쿠키에서 refresh token을 읽는다.
  // 쿠키가 없으면 401(INVALID_REFRESH_TOKEN). 새 access token만 본문으로 반환한다(refresh 쿠키는 그대로).
  @PostMapping("/reissue")
  public ResponseEntity<TokenResponse> reissue(
      @CookieValue(name = "refreshToken", required = false) String refreshToken) {
    if (refreshToken == null) {
      throw new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN);
    }
    TokenPair tokens = authService.reissue(refreshToken);
    return ResponseEntity.ok()
        .body(TokenResponse.bearer(tokens.accessToken(), tokens.accessTokenValiditySeconds()));
  }

  // 단계 5: 쿠키에서 refresh token을 읽어 서버에서 지우고(idempotent), 클라이언트 쿠키도 maxAge=0으로 만료시킨다.
  // 단계 15: Authorization 헤더의 access token도 함께 넘겨 "즉시 폐기"(denylist)한다 —
  // 이전 주석의 "강제 무효화는 토큰 블랙리스트 필요(후속 주제)"가 바로 이 단계에서 구현됐다.
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = "refreshToken", required = false) String refreshToken,
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    String accessToken = null;
    if (authorization != null && authorization.startsWith("Bearer ")) {
      accessToken = authorization.substring("Bearer ".length());
    }
    authService.logout(refreshToken, accessToken);
    ResponseCookie expired = refreshCookieFactory.expire();
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, expired.toString())
        .build();
  }
}
