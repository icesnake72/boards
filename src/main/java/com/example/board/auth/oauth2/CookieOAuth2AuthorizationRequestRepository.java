package com.example.board.auth.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.stereotype.Component;

// 단계 8: 인가 요청(state 포함)을 쿠키에 보관하는 저장소.
//
// oauth2Login의 기본 저장소는 HttpSession인데 우리 서버는 STATELESS(단계 3부터)라 쓸 수 없다.
// 단계 7에서 손으로 만들던 state 쿠키의 "표준 인터페이스 구현판"이 바로 이 클래스다 —
// 표준 라이브러리를 써도 우리 환경(무세션)에 맞는 어댑터 하나는 결국 우리가 만든다.
//
// 보안: 객체를 통째로 직렬화(JDK serialization)해 쿠키에 넣으면 클라이언트가 조작한
// 바이트를 역직렬화하게 된다(insecure deserialization). 필요한 필드만 JSON으로 담고
// 꺼낼 때 builder로 재조립한다.
@Slf4j
@Component
public class CookieOAuth2AuthorizationRequestRepository
    implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

  static final String COOKIE_NAME = "oauthRequest";
  private static final Duration TTL = Duration.ofMinutes(5);

  private final ObjectMapper objectMapper;
  private final boolean cookieSecure;

  public CookieOAuth2AuthorizationRequestRepository(
      ObjectMapper objectMapper,
      @Value("${app.refresh-cookie.secure}") boolean cookieSecure) {
    this.objectMapper = objectMapper;
    this.cookieSecure = cookieSecure;
  }

  // 쿠키에 담는 최소 필드 — 콜백 검증과 토큰 교환 재개에 필요한 것만
  record StoredRequest(
      String state,
      String authorizationUri,
      String clientId,
      String redirectUri,
      Set<String> scopes,
      String registrationId,
      // 단계 9 nonce 보강: OIDC일 때 라이브러리가 attribute로 실어 나르는 난수 원본.
      // 이 값을 유실하면 id_token의 nonce 검증이 조용히 스킵된다(재사용/주입 방어 소실).
      // 순수 OAuth2(카카오)에는 없는 값이라 null 허용.
      String nonce
  ) {
  }

  // 로그인 시작(302 직전)에 호출된다 — 인가 요청을 쿠키로 내려보낸다
  @Override
  public void saveAuthorizationRequest(
      OAuth2AuthorizationRequest authorizationRequest,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (authorizationRequest == null) {
      expireCookie(response);
      return;
    }
    StoredRequest stored = new StoredRequest(
        // CSRF 방어용 난수 — 콜백의 state 파라미터와 대조할 기준값 (예: "DeYsqi..." base64url)
        authorizationRequest.getState(),
        // 인가 페이지 주소 — provider.kakao.authorization-uri (https://kauth.kakao.com/oauth/authorize)
        authorizationRequest.getAuthorizationUri(),
        // 우리 앱의 client_id — registration.kakao.client-id (카카오 REST API 키)
        authorizationRequest.getClientId(),
        // {baseUrl} 치환이 끝난 콜백 주소 — 토큰 교환 때 같은 값을 다시 보내야 카카오가 승인한다
        authorizationRequest.getRedirectUri(),
        // 요청한 동의 항목 — registration.kakao.scope (예: [profile_nickname])
        authorizationRequest.getScopes(),
        // 어느 registration의 요청인지("kakao") — 콜백에서 설정을 다시 찾는 열쇠
        authorizationRequest.getAttribute(OAuth2ParameterNames.REGISTRATION_ID),
        // 단계 9 nonce 보강: OIDC 요청에만 존재(순수 OAuth2는 null). 복원 시 이 값이 없으면
        // OidcAuthorizationCodeAuthenticationProvider가 nonce 검증을 건너뛴다
        authorizationRequest.getAttribute(OidcParameterNames.NONCE));
    try {
      String json = objectMapper.writeValueAsString(stored);
      String value = Base64.getUrlEncoder()
          .encodeToString(json.getBytes(StandardCharsets.UTF_8));
      response.addHeader(HttpHeaders.SET_COOKIE, cookie(value, TTL).toString());
    } catch (Exception e) {
      throw new IllegalStateException("인가 요청 쿠키 직렬화 실패", e);
    }
  }

  // 콜백에서 호출된다 — 쿠키를 읽어 인가 요청을 복원한다 (라이브러리가 state 대조에 사용)
  @Override
  public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
    Cookie cookie = findCookie(request);
    if (cookie == null) {
      return null;  // 쿠키 없음 → 라이브러리가 authorization_request_not_found로 인증 실패 처리
    }
    try {
      String json = new String(
          Base64.getUrlDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
      StoredRequest stored = objectMapper.readValue(json, StoredRequest.class);
      return OAuth2AuthorizationRequest.authorizationCode()
          .state(stored.state())
          .authorizationUri(stored.authorizationUri())
          .clientId(stored.clientId())
          .redirectUri(stored.redirectUri())
          .scopes(stored.scopes())
          .attributes(attrs -> {
            attrs.put(OAuth2ParameterNames.REGISTRATION_ID, stored.registrationId());
            // 단계 9 nonce 보강: OIDC 요청이면 원본을 attribute로 되돌려 놓아야
            // 라이브러리의 nonce 대조가 실제로 수행된다 (null이면 저장 때부터 순수 OAuth2)
            if (stored.nonce() != null) {
              attrs.put(OidcParameterNames.NONCE, stored.nonce());
            }
          })
          .build();
    } catch (Exception e) {
      // 조작·손상된 쿠키는 없는 것으로 취급한다 — 예외 상세는 로그로만
      log.warn("인가 요청 쿠키 복원 실패: {}", e.getMessage());
      return null;
    }
  }

  // 콜백 처리 시 라이브러리가 호출한다 — 복원해서 돌려주고 쿠키는 즉시 만료(1회용)
  @Override
  public OAuth2AuthorizationRequest removeAuthorizationRequest(
      HttpServletRequest request, HttpServletResponse response) {
    OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
    expireCookie(response);
    return authorizationRequest;
  }

  private Cookie findCookie(HttpServletRequest request) {
    if (request.getCookies() == null) {
      return null;
    }
    for (Cookie cookie : request.getCookies()) {
      if (COOKIE_NAME.equals(cookie.getName())) {
        return cookie;
      }
    }
    return null;
  }

  private void expireCookie(HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
  }

  // 단계 7 state 쿠키와 같은 이유로 SameSite=Lax — 콜백이 카카오發 크로스 사이트 이동이라
  // Strict이면 쿠키가 실리지 않아 검증이 항상 실패한다
  private ResponseCookie cookie(String value, Duration maxAge) {
    return ResponseCookie.from(COOKIE_NAME, value)
        .httpOnly(true)
        .secure(cookieSecure)
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build();
  }
}
