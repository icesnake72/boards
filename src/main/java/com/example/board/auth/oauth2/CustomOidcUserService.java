package com.example.board.auth.oauth2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 단계 9: 구글을 OIDC(OpenID Connect) 경로로 전환하며 필요해진 "OIDC판 사용자 로딩" 지점.
//
// 왜 클래스가 하나 더 필요한가 — scope에 openid를 넣는 순간 라이브러리의 담당자가 바뀐다:
//   순수 OAuth2: OAuth2LoginAuthenticationProvider → DefaultOAuth2UserService(→ CustomOAuth2UserService)
//   OIDC:       OidcAuthorizationCodeAuthenticationProvider → OidcUserService(→ 이 클래스)
// 즉 openid scope가 있으면 CustomOAuth2UserService는 아예 호출되지 않는다.
// 우리 find-or-create를 OIDC 경로에도 연결하려면 OidcUser를 다루는 별도 서비스가 필요하다.
//
// OIDC의 차이: 토큰 응답에 id_token(서명된 JWT)이 함께 오고, sub/email/name 같은 사용자 정보가
// 그 claims에 이미 들어 있다. 서명 검증은 위임(delegate) 내부에서 라이브러리가 끝낸다.
// claims 구조가 구글 userinfo 응답과 같은 평면 JSON(sub/email/name)이라
// CustomOAuth2UserService.upsertUser()의 GOOGLE 분기를 그대로 재사용할 수 있다.
//
// 카카오는 openid 없이 순수 OAuth2를 유지하므로 계속 CustomOAuth2UserService를 탄다 — 두 경로 병행.
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

  private final CustomOAuth2UserService customOAuth2UserService;

  // 표준 OidcUserService에 위임 — id_token 검증/userinfo 병합 등 프로토콜 처리는 전부 이쪽 몫.
  // DefaultOAuth2UserService처럼 상속하지 않는 이유: OidcUserService는 final 메서드가 아니라
  // 구성(composition)이 권장되는 구조이고, 테스트에서 교체하기도 쉽다.
  private OAuth2UserService<OidcUserRequest, OidcUser> delegate = new OidcUserService();

  // 테스트 전용 — 실제 구글 호출 없이 delegate를 stub으로 교체한다
  void setDelegate(OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
    this.delegate = delegate;
  }

  @Override
  @Transactional
  public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
    // id_token 서명 검증과 (scope에 따라) userinfo 조회는 표준 구현이 끝낸다
    OidcUser oidcUser = delegate.loadUser(userRequest);
    String registrationId = userRequest.getClientRegistration().getRegistrationId();
    // getAttributes() = id_token claims(+userinfo) — 평면 sub/email/name이라 GOOGLE 분기 재사용
    customOAuth2UserService.upsertUser(registrationId, oidcUser.getAttributes());
    log.debug("OIDC 사용자 로딩 완료: registrationId={}", registrationId);
    // principal은 표준 타입 그대로 — getName()이 sub이므로 SuccessHandler도 수정 없이 동작한다
    return oidcUser;
  }
}
