package com.example.board.auth.oauth2;

import com.example.board.auth.AuthService;
import com.example.board.auth.RefreshCookieFactory;
import com.example.board.auth.dto.TokenPair;
import com.example.board.auth.dto.TokenResponse;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

// 단계 8: OAuth 로그인 성공 후 "우리 토큰"을 발급하는 지점 —
// 단계 7 KakaoOAuthController.callback()의 성공 응답 부분이 여기로 이사 왔다.
// 응답 형태는 단계 5부터의 규칙 그대로: access token은 본문(JSON), refresh token은 httpOnly 쿠키.
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

  private final UserRepository userRepository;
  private final AuthService authService;
  private final RefreshCookieFactory refreshCookieFactory;
  private final ObjectMapper objectMapper;

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request,
      HttpServletResponse response,
      Authentication authentication) throws IOException {
    // registrationId("kakao") → AuthProvider.KAKAO. 네이버/구글을 추가해도 이 핸들러는 그대로다.
    OAuth2AuthenticationToken oauth2Token = (OAuth2AuthenticationToken) authentication;
    AuthProvider provider = AuthProvider.valueOf(
        oauth2Token.getAuthorizedClientRegistrationId().toUpperCase(Locale.ROOT));

    // user-name-attribute: id 덕분에 getName() = 카카오 회원번호 = providerId (단계 7과 같은 값).
    // CustomOAuth2UserService가 방금 upsert했으므로 반드시 존재한다.
    String providerId = authentication.getName();
    User user = userRepository.findByProviderAndProviderId(provider, providerId)
        .orElseThrow(() -> new IllegalStateException(
            "OAuth 로그인 직후 사용자가 없음: provider=" + provider + ", providerId=" + providerId));

    TokenPair tokens = authService.issueTokenPair(user);
    log.info("OAuth 로그인 성공(표준 경로): username={}", user.getUsername());

    // SPA 전환 처리: 소셜 로그인은 전체 리다이렉트 흐름이라, JSON을 응답하면 브라우저에
    // 날 JSON이 그대로 떠 버린다(SPA가 본문을 받을 수 없음). 그래서 refresh 쿠키만 심고
    // SPA 루트("/")로 리다이렉트한다 — SPA는 로드 시 silent login(reissue)으로 이 쿠키를
    // 사용해 access token을 얻는다. access token을 URL에 싣지 않으므로 노출 위험도 없다.
    response.addHeader(HttpHeaders.SET_COOKIE,
        refreshCookieFactory.create(tokens.refreshToken(), tokens.refreshTokenValiditySeconds())
            .toString());
    response.sendRedirect("/");
    // SPA 전환 처리에 의해 제거 — 기존 JSON 응답(access token 본문):
    // response.setStatus(HttpServletResponse.SC_OK);
    // response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
    // objectMapper.writeValue(response.getWriter(),
    //     TokenResponse.bearer(tokens.accessToken(), tokens.accessTokenValiditySeconds()));
  }
}
