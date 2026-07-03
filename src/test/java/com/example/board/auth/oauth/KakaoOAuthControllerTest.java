package com.example.board.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.example.board.auth.RefreshCookieFactory;
import com.example.board.auth.dto.TokenPair;
import com.example.board.auth.dto.TokenResponse;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.UnauthorizedException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

// state 발급/검증과 쿠키 배치는 컨트롤러 책임이므로 스프링 컨텍스트 없이 단위 테스트한다.
class KakaoOAuthControllerTest {

  KakaoOAuthService kakaoOAuthService;
  KakaoOAuthController controller;

  @BeforeEach
  void setUp() {
    kakaoOAuthService = mock(KakaoOAuthService.class);
    RefreshCookieFactory cookieFactory =
        new RefreshCookieFactory("refreshToken", false, "Strict", "/api/v1/auth");
    controller = new KakaoOAuthController(kakaoOAuthService, cookieFactory, false);
  }

  @Test
  void should_redirectToKakao_withStateCookieMatchingStateParam() {
    given(kakaoOAuthService.authorizeUrl(org.mockito.ArgumentMatchers.anyString()))
        .willAnswer(inv -> "https://kauth.kakao.com/oauth/authorize?state=" + inv.getArgument(0));

    ResponseEntity<Void> response = controller.login();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    String location = String.valueOf(response.getHeaders().getLocation());
    String stateInUrl = location.substring(location.indexOf("state=") + 6);
    List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    assertThat(setCookies).anySatisfy(cookie -> {
      assertThat(cookie).startsWith("oauthState=" + stateInUrl);
      // 콜백은 카카오發 크로스 사이트 이동이라 Lax여야 쿠키가 실린다
      assertThat(cookie).contains("SameSite=Lax");
      assertThat(cookie).contains("HttpOnly");
    });
  }

  @Test
  void should_throwOauthLoginFailed_whenKakaoReturnsError() {
    assertThatThrownBy(() -> controller.callback(null, "state", "access_denied", "state"))
        .isInstanceOf(UnauthorizedException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.OAUTH_LOGIN_FAILED);
  }

  @Test
  void should_throwInvalidState_whenStateMismatch() {
    assertThatThrownBy(() -> controller.callback("code", "tampered-state", null, "issued-state"))
        .isInstanceOf(UnauthorizedException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_STATE);
  }

  @Test
  void should_throwInvalidState_whenStateCookieMissing() {
    assertThatThrownBy(() -> controller.callback("code", "state", null, null))
        .isInstanceOf(UnauthorizedException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.INVALID_OAUTH_STATE);
  }

  @Test
  void should_returnAccessTokenAndRefreshCookie_whenCallbackSucceeds() {
    given(kakaoOAuthService.login("valid-code"))
        .willReturn(new TokenPair("our-access", "our-refresh", 3600, 1209600));

    ResponseEntity<TokenResponse> response =
        controller.callback("valid-code", "state-1", null, "state-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().accessToken()).isEqualTo("our-access");
    assertThat(response.getBody().tokenType()).isEqualTo("Bearer");

    List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    // refresh token은 httpOnly 쿠키로, state 쿠키는 즉시 만료(Max-Age=0)로 내려간다
    assertThat(setCookies).anySatisfy(cookie ->
        assertThat(cookie).startsWith("refreshToken=our-refresh").contains("HttpOnly"));
    assertThat(setCookies).anySatisfy(cookie ->
        assertThat(cookie).startsWith("oauthState=;").contains("Max-Age=0"));
  }
}
