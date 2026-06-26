package com.example.board.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

// 강의 포인트(단계 5): refresh token을 담는 Set-Cookie 헤더를 만든다.
// - HttpOnly: JS(document.cookie)가 못 읽어 XSS로 토큰을 탈취당하지 않는다.
// - Secure  : HTTPS에서만 전송. 로컬은 HTTP라 false(true면 쿠키가 안 실려 테스트가 깨진다). 운영에선 반드시 true.
// - SameSite: 크로스 사이트 요청에 쿠키 동봉을 제한해 CSRF 면을 줄인다(Strict이 가장 보수적).
// - Path    : 이 경로 하위 요청에만 쿠키가 전송돼 노출 면적을 좁힌다(/api/v1/auth).
@Component
public class RefreshCookieFactory {

  private final String name;
  private final boolean secure;
  private final String sameSite;
  private final String path;

  public RefreshCookieFactory(
      @Value("${app.refresh-cookie.name}") String name,
      @Value("${app.refresh-cookie.secure}") boolean secure,
      @Value("${app.refresh-cookie.same-site}") String sameSite,
      @Value("${app.refresh-cookie.path}") String path) {
    this.name = name;
    this.secure = secure;
    this.sameSite = sameSite;
    this.path = path;
  }

  public String cookieName() {
    return name;
  }

  // 발급용: refresh token을 담은 httpOnly 쿠키
  public ResponseCookie create(String refreshToken, long maxAgeSeconds) {
    return ResponseCookie.from(name, refreshToken)
        .httpOnly(true)
        .secure(secure)
        .sameSite(sameSite)
        .path(path)
        .maxAge(maxAgeSeconds)
        .build();
  }

  // 삭제용: 같은 name/path에 빈 값 + maxAge=0으로 클라이언트 쿠키를 즉시 만료시킨다
  public ResponseCookie expire() {
    return ResponseCookie.from(name, "")
        .httpOnly(true)
        .secure(secure)
        .sameSite(sameSite)
        .path(path)
        .maxAge(0)
        .build();
  }
}
