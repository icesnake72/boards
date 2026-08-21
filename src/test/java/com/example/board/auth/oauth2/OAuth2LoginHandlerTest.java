package com.example.board.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.example.board.auth.AuthService;
import com.example.board.auth.RefreshCookieFactory;
import com.example.board.auth.dto.TokenPair;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

// SPA 전환 처리 검증: 소셜 로그인 성공/실패 핸들러가 JSON이 아니라
// "refresh 쿠키 + 리다이렉트"로 SPA에 제어를 돌려주는지 확인한다.
@ExtendWith(MockitoExtension.class)
class OAuth2LoginHandlerTest {

  @Mock UserRepository userRepository;
  @Mock AuthService authService;
  @Mock RefreshCookieFactory refreshCookieFactory;

  private final ObjectMapper objectMapper = new ObjectMapper();

  // 성공: refresh 쿠키를 심고 SPA 루트("/")로 302 — 본문에 access token을 싣지 않는다
  @Test
  void should_setRefreshCookieAndRedirectRoot_whenOauthLoginSucceeds() throws Exception {
    OAuth2User principal = new DefaultOAuth2User(
        java.util.List.of(), Map.of("id", 12345L), "id");
    OAuth2AuthenticationToken authentication =
        new OAuth2AuthenticationToken(principal, java.util.List.of(), "kakao");

    User user = new User(
        "kakao_12345", "k@t.com", "pw", com.example.board.user.Role.USER,
        AuthProvider.KAKAO, "12345");
    given(userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, "12345"))
        .willReturn(Optional.of(user));
    given(authService.issueTokenPair(user))
        .willReturn(new TokenPair("access-token", "refresh-token", 3600, 1209600));
    given(refreshCookieFactory.create(anyString(), anyLong()))
        .willReturn(ResponseCookie.from("refreshToken", "refresh-token").httpOnly(true).build());

    MockHttpServletResponse response = new MockHttpServletResponse();
    new OAuth2LoginSuccessHandler(userRepository, authService, refreshCookieFactory, objectMapper)
        .onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

    assertThat(response.getStatus()).isEqualTo(302);
    assertThat(response.getRedirectedUrl()).isEqualTo("/");
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("refreshToken=refresh-token");
    assertThat(response.getContentAsString()).doesNotContain("access-token"); // 본문 노출 금지
  }

  // 실패: 401 JSON이 아니라 에러 코드를 쿼리에 실어 SPA 루트로 302
  @Test
  void should_redirectRootWithErrorQuery_whenOauthLoginFails() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    new OAuth2LoginFailureHandler(objectMapper)
        .onAuthenticationFailure(new MockHttpServletRequest(), response,
            new BadCredentialsException("state mismatch"));

    assertThat(response.getStatus()).isEqualTo(302);
    assertThat(response.getRedirectedUrl()).isEqualTo("/?error=OAUTH_LOGIN_FAILED");
  }
}
