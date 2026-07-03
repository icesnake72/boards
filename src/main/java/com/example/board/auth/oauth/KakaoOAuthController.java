package com.example.board.auth.oauth;

import com.example.board.auth.RefreshCookieFactory;
import com.example.board.auth.dto.TokenPair;
import com.example.board.auth.dto.TokenResponse;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.UnauthorizedException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 카카오 OAuth2 HTTP 진입점 — 두 엔드포인트가 authorization code 흐름의 양 끝이다.
// GET /login    : state 쿠키를 심고 카카오 인가 페이지로 302 (흐름의 시작)
// GET /callback : 카카오가 code를 들고 돌아오는 곳 (흐름의 끝, 우리 JWT 발급)
// 강의 포인트: 로컬 로그인(POST /auth/login)과 달리 둘 다 GET이다 — 브라우저 리다이렉트로 오가기 때문.
@Slf4j
@RestController
@RequestMapping("/api/oauth/kakao")
public class KakaoOAuthController {

  static final String STATE_COOKIE_NAME = "oauthState";
  private static final Duration STATE_TTL = Duration.ofMinutes(5);

  private final KakaoOAuthService kakaoOAuthService;
  private final RefreshCookieFactory refreshCookieFactory;
  private final boolean cookieSecure;

  public KakaoOAuthController(
      KakaoOAuthService kakaoOAuthService,
      RefreshCookieFactory refreshCookieFactory,
      @Value("${app.refresh-cookie.secure}") boolean cookieSecure) {
    this.kakaoOAuthService = kakaoOAuthService;
    this.refreshCookieFactory = refreshCookieFactory;
    this.cookieSecure = cookieSecure;
  }

  // 로그인 시작 — CSRF 방지용 랜덤 state를 만들어 (1) 쿠키에 심고 (2) 인가 URL에도 실어 보낸다.
  // 콜백에서 둘을 대조해, 공격자가 임의로 만든 콜백 요청(다른 사람의 code 주입)을 걸러낸다.
  @GetMapping("/login")
  public ResponseEntity<Void> login() {
    String state = UUID.randomUUID().toString();
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(kakaoOAuthService.authorizeUrl(state)))
        .header(HttpHeaders.SET_COOKIE, stateCookie(state, STATE_TTL).toString())
        .build();
  }

  // 카카오가 되돌려주는 콜백. 성공 시 access token은 본문, refresh token은 httpOnly 쿠키(단계 5와 동일).
  // error 파라미터는 사용자가 동의 화면에서 [취소]를 눌렀을 때 등 카카오 쪽 거절 사유가 담겨 온다.
  @GetMapping("/callback")
  public ResponseEntity<TokenResponse> callback(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String error,
      @CookieValue(name = STATE_COOKIE_NAME, required = false) String stateCookie) {
    if (error != null || code == null) {
      log.warn("카카오 인가 거절/실패: error={}", error);
      throw new UnauthorizedException(ErrorCode.OAUTH_LOGIN_FAILED);
    }
    if (stateCookie == null || !stateCookie.equals(state)) {
      log.warn("OAuth state 불일치: cookie={}, param={}", stateCookie, state);
      throw new UnauthorizedException(ErrorCode.INVALID_OAUTH_STATE);
    }

    TokenPair tokens = kakaoOAuthService.login(code);
    ResponseCookie refreshCookie =
        refreshCookieFactory.create(tokens.refreshToken(), tokens.refreshTokenValiditySeconds());
    return ResponseEntity.ok()
        // Set-Cookie는 같은 이름의 헤더를 여러 개 보낼 수 있다 — refresh 발급 + state 즉시 만료
        .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())
        .header(HttpHeaders.SET_COOKIE, stateCookie("", Duration.ZERO).toString())
        .body(TokenResponse.bearer(tokens.accessToken(), tokens.accessTokenValiditySeconds()));
  }

  // state 쿠키는 SameSite=Lax여야 한다 — 콜백은 kauth.kakao.com발 크로스 사이트 최상위 이동(GET)이라
  // Strict이면 브라우저가 쿠키를 아예 실어주지 않아 검증이 항상 실패한다. (refresh 쿠키의 Strict과 대비되는 지점)
  private ResponseCookie stateCookie(String value, Duration maxAge) {
    return ResponseCookie.from(STATE_COOKIE_NAME, value)
        .httpOnly(true)
        .secure(cookieSecure)
        .sameSite("Lax")
        .path("/api/oauth/kakao")
        .maxAge(maxAge)
        .build();
  }
}
