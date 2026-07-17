package com.example.board.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;

// 쿠키 저장소의 save → load → remove 왕복과 불량 쿠키 방어를 검증한다 (스프링 컨텍스트 불필요).
class CookieOAuth2AuthorizationRequestRepositoryTest {

  CookieOAuth2AuthorizationRequestRepository repository;

  @BeforeEach
  void setUp() {
    repository = new CookieOAuth2AuthorizationRequestRepository(new ObjectMapper(), false);
  }

  private OAuth2AuthorizationRequest sampleRequest() {
    return OAuth2AuthorizationRequest.authorizationCode()
        .state("state-123")
        .authorizationUri("https://kauth.kakao.com/oauth/authorize")
        .clientId("test-appkey")
        .redirectUri("http://localhost:8090/login/oauth2/code/kakao")
        .scopes(Set.of("profile_nickname"))
        .attributes(attrs -> attrs.put(OAuth2ParameterNames.REGISTRATION_ID, "kakao"))
        .build();
  }

  // 응답의 Set-Cookie 값을 요청 쿠키로 옮긴다 — 브라우저가 하는 일을 흉내
  private MockHttpServletRequest requestWithCookieFrom(MockHttpServletResponse response) {
    String setCookie = response.getHeader("Set-Cookie");
    String value = setCookie.split(";")[0]
        .substring((CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME + "=").length());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME, value));
    return request;
  }

  @Test
  void should_roundTripAuthorizationRequest_throughCookie() {
    MockHttpServletResponse saveResponse = new MockHttpServletResponse();
    repository.saveAuthorizationRequest(
        sampleRequest(), new MockHttpServletRequest(), saveResponse);

    String setCookie = saveResponse.getHeader("Set-Cookie");
    assertThat(setCookie).contains("oauthRequest=").contains("HttpOnly").contains("SameSite=Lax");

    OAuth2AuthorizationRequest loaded =
        repository.loadAuthorizationRequest(requestWithCookieFrom(saveResponse));

    assertThat(loaded).isNotNull();
    assertThat(loaded.getState()).isEqualTo("state-123");
    assertThat(loaded.getClientId()).isEqualTo("test-appkey");
    assertThat(loaded.getRedirectUri()).isEqualTo("http://localhost:8090/login/oauth2/code/kakao");
    assertThat(loaded.<String>getAttribute(OAuth2ParameterNames.REGISTRATION_ID))
        .isEqualTo("kakao");
  }

  @Test
  void should_returnNullAndExpireCookie_whenRemove() {
    MockHttpServletResponse saveResponse = new MockHttpServletResponse();
    repository.saveAuthorizationRequest(
        sampleRequest(), new MockHttpServletRequest(), saveResponse);
    MockHttpServletResponse removeResponse = new MockHttpServletResponse();

    OAuth2AuthorizationRequest removed = repository.removeAuthorizationRequest(
        requestWithCookieFrom(saveResponse), removeResponse);

    assertThat(removed).isNotNull();
    // 1회용 — remove 후 쿠키는 즉시 만료된다
    assertThat(removeResponse.getHeader("Set-Cookie")).contains("Max-Age=0");
  }

  // OIDC(구글) 요청 — 라이브러리가 nonce attribute를 실어 온다
  private OAuth2AuthorizationRequest oidcRequest() {
    return OAuth2AuthorizationRequest.authorizationCode()
        .state("state-oidc")
        .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
        .clientId("google-client")
        .redirectUri("http://localhost:8090/login/oauth2/code/google")
        .scopes(Set.of("openid", "profile", "email"))
        .attributes(attrs -> {
          attrs.put(OAuth2ParameterNames.REGISTRATION_ID, "google");
          attrs.put(OidcParameterNames.NONCE, "nonce-raw-xyz");
        })
        .build();
  }

  // 단계 9 회귀 방어: nonce를 유실하면 id_token 재사용/주입 검증이 조용히 스킵된다
  @Test
  void should_preserveNonce_whenOidcRequestRoundTrips() {
    MockHttpServletResponse saveResponse = new MockHttpServletResponse();
    repository.saveAuthorizationRequest(
        oidcRequest(), new MockHttpServletRequest(), saveResponse);

    OAuth2AuthorizationRequest loaded =
        repository.loadAuthorizationRequest(requestWithCookieFrom(saveResponse));

    assertThat(loaded).isNotNull();
    assertThat(loaded.<String>getAttribute(OidcParameterNames.NONCE)).isEqualTo("nonce-raw-xyz");
  }

  // 순수 OAuth2(카카오)에는 nonce가 없어야 한다 — attribute가 없으면 라이브러리가 검증을 건너뛴다
  @Test
  void should_haveNoNonceAttribute_whenPlainOAuth2RoundTrips() {
    MockHttpServletResponse saveResponse = new MockHttpServletResponse();
    repository.saveAuthorizationRequest(
        sampleRequest(), new MockHttpServletRequest(), saveResponse);

    OAuth2AuthorizationRequest loaded =
        repository.loadAuthorizationRequest(requestWithCookieFrom(saveResponse));

    assertThat(loaded).isNotNull();
    assertThat(loaded.<String>getAttribute(OidcParameterNames.NONCE)).isNull();
  }

  @Test
  void should_returnNull_whenCookieMissing() {
    assertThat(repository.loadAuthorizationRequest(new MockHttpServletRequest())).isNull();
  }

  @Test
  void should_returnNull_whenCookieTampered() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(
        CookieOAuth2AuthorizationRequestRepository.COOKIE_NAME, "garbage-not-base64-json"));

    assertThat(repository.loadAuthorizationRequest(request)).isNull();
  }
}
