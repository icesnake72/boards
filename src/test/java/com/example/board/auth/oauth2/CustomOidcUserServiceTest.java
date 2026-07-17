package com.example.board.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.board.profile.UserProfile;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.transaction.annotation.Transactional;

// 단계 9: OIDC 경로 검증 — delegate(표준 OidcUserService)를 stub으로 바꿔 실제 구글 호출 없이
// "id_token claims → upsertUser" 연결만 확인한다. 가입 정책 자체는 CustomOAuth2UserServiceTest 담당.
@SpringBootTest
@Transactional
class CustomOidcUserServiceTest {

  @Autowired
  CustomOidcUserService customOidcUserService;

  @Autowired
  CustomOAuth2UserService customOAuth2UserService;

  @Autowired
  UserRepository userRepository;

  @Autowired
  UserProfileRepository userProfileRepository;

  private static final String GOOGLE_SUB = "115850938347103859274";
  private static final String CLIENT_ID = "test-google-client-id";

  @BeforeEach
  void stubDelegate() {
    // 표준 OidcUserService라면 id_token 서명 검증 후 DefaultOidcUser를 만든다 — 그 결과만 흉내 낸다.
    // DefaultOidcUser의 getAttributes()는 id_token claims, getName()은 sub이다.
    customOidcUserService.setDelegate(request ->
        new DefaultOidcUser(
            AuthorityUtils.createAuthorityList("OIDC_USER"), request.getIdToken()));
  }

  @AfterEach
  void restoreDelegate() {
    // customOidcUserService는 캐시된 컨텍스트의 싱글톤 — stub을 남기면 다른 테스트가 오염된다
    customOidcUserService.setDelegate(new OidcUserService());
  }

  // openid scope가 든 구글 registration — 테스트 안에서 직접 조립한다 (yaml 더미 등록 불필요)
  private ClientRegistration googleRegistration() {
    return ClientRegistration.withRegistrationId("google")
        .clientId(CLIENT_ID)
        .clientSecret("test-google-secret")
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("{baseUrl}/login/oauth2/code/google")
        .scope("openid", "profile", "email")
        .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
        .tokenUri("https://oauth2.googleapis.com/token")
        .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
        .userNameAttributeName(IdTokenClaimNames.SUB)
        .build();
  }

  private OidcUserRequest oidcUserRequest(String sub, String email, String name) {
    OAuth2AccessToken accessToken = new OAuth2AccessToken(
        OAuth2AccessToken.TokenType.BEARER, "test-access-token",
        Instant.now(), Instant.now().plusSeconds(3600));
    // 실제라면 구글이 서명한 JWT — 검증은 delegate 몫이라 여기서는 claims만 채운다
    OidcIdToken idToken = OidcIdToken.withTokenValue("test-id-token")
        .issuer("https://accounts.google.com")
        .subject(sub)
        .audience(List.of(CLIENT_ID))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(3600))
        .claim("email", email)
        .claim("name", name)
        .build();
    return new OidcUserRequest(googleRegistration(), accessToken, idToken);
  }

  @Test
  void should_upsertNewUser_whenFirstOidcLogin() {
    OidcUser oidcUser = customOidcUserService.loadUser(
        oidcUserRequest(GOOGLE_SUB, "oidc@gmail.com", "OIDC테스터"));

    // SuccessHandler가 쓰는 값 — OIDC에서도 getName() = sub = providerId
    assertThat(oidcUser.getName()).isEqualTo(GOOGLE_SUB);
    User user = userRepository
        .findByProviderAndProviderId(AuthProvider.GOOGLE, GOOGLE_SUB).orElseThrow();
    assertThat(user.getUsername()).isEqualTo("google_" + GOOGLE_SUB);
    assertThat(user.getEmail()).isEqualTo("oidc@gmail.com");
    UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
    assertThat(profile.getNickname()).isEqualTo("OIDC테스터");
  }

  @Test
  void should_reuseExistingGoogleUser_whenOidcLogin() {
    // 단계 8(순수 OAuth2 경로)로 이미 가입된 구글 사용자 — OIDC 전환 후에도 같은 계정이어야 한다
    User existing = customOAuth2UserService.upsertUser("google",
        Map.of("sub", GOOGLE_SUB, "email", "oidc@gmail.com", "name", "OIDC테스터"));
    long count = userRepository.count();

    customOidcUserService.loadUser(oidcUserRequest(GOOGLE_SUB, "oidc@gmail.com", "OIDC테스터"));

    assertThat(userRepository.count()).isEqualTo(count);
    User found = userRepository
        .findByProviderAndProviderId(AuthProvider.GOOGLE, GOOGLE_SUB).orElseThrow();
    assertThat(found.getId()).isEqualTo(existing.getId());
  }
}
