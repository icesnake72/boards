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
