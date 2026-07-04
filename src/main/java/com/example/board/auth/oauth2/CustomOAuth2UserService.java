package com.example.board.auth.oauth2;

import com.example.board.profile.UserProfile;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.AuthProvider;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 단계 8: 단계 3의 CustomUserDetailsService에 대응하는 "OAuth판 사용자 로딩" 지점.
//
// super.loadUser()가 제공자의 사용자 정보 API 호출을 대신하고,
// 우리가 추가하는 것은 그 결과를 우리 users 테이블에 연결하는 find-or-create뿐이다.
//
// 구글 추가(단계 8 확장)로 제공자가 둘이 됐다 — 제공자마다 응답 JSON 구조가 다르므로,
// 그 차이를 extractUserInfo() 한 곳으로 모은다. 나머지 가입/재사용 정책은 제공자와 무관하게 공통이다.
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

  private final UserRepository userRepository;
  private final UserProfileRepository userProfileRepository;
  private final PasswordEncoder passwordEncoder;

  // 제공자 응답에서 우리가 쓰는 세 값만 추린 것 — 어느 제공자든 이 모양으로 정규화된다
  record OAuth2UserInfo(String providerId, String email, String nickname) {
  }

  @Override
  @Transactional
  public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
    // 토큰 교환은 이미 라이브러리가 끝냈고, 여기서 제공자의 사용자 정보를 조회한다
    OAuth2User oauth2User = super.loadUser(userRequest);
    // 어느 registration의 로그인인지("kakao"/"google")는 요청에 실려 온다
    String registrationId = userRequest.getClientRegistration().getRegistrationId();
    upsertUser(registrationId, oauth2User.getAttributes());
    // principal은 표준 타입 그대로 반환 — SuccessHandler가 getName()(providerId)으로 우리 사용자를 찾는다
    return oauth2User;
  }

  // find-or-create — (provider, providerId)로 찾고 없으면 가입 (정책은 단계 7과 동일)
  User upsertUser(String registrationId, Map<String, Object> attributes) {
    AuthProvider provider = AuthProvider.valueOf(registrationId.toUpperCase(Locale.ROOT));
    OAuth2UserInfo userInfo = extractUserInfo(provider, attributes);
    return userRepository.findByProviderAndProviderId(provider, userInfo.providerId())
        .orElseGet(() -> createUser(provider, registrationId, userInfo));
  }

  // 제공자별 응답 구조 차이는 여기가 유일한 분기점이다.
  // 카카오: 중첩 JSON (id / kakao_account.email / kakao_account.profile.nickname)
  // 구글:   평면 JSON (sub / email / name) — user-name-attribute도 sub
  private OAuth2UserInfo extractUserInfo(AuthProvider provider, Map<String, Object> attributes) {
    return switch (provider) {
      case KAKAO -> new OAuth2UserInfo(
          String.valueOf(attributes.get("id")),
          kakaoEmail(attributes),
          kakaoNickname(attributes));
      case GOOGLE -> new OAuth2UserInfo(
          (String) attributes.get("sub"),
          (String) attributes.get("email"),
          (String) attributes.get("name"));
      case LOCAL -> throw new IllegalStateException("LOCAL은 소셜 제공자가 아니다");
    };
  }

  private User createUser(AuthProvider provider, String registrationId, OAuth2UserInfo userInfo) {
    // kakao_4614.., google_1076.. — registration ID가 접두사라 제공자 간 충돌이 없다
    String username = registrationId + "_" + userInfo.providerId();

    // 이메일 미동의(null)이거나 기존 계정과 겹치면 대체 이메일 (계정 연동은 후속 주제)
    String email = userInfo.email();
    if (email == null || userRepository.existsByEmail(email)) {
      email = username + "@" + registrationId + ".local";
    }

    // 소셜 사용자는 password 로그인 불가 — 아무도 모르는 랜덤 값을 해시해 저장
    String password = passwordEncoder.encode(UUID.randomUUID().toString());

    User user = userRepository.save(
        new User(username, email, password, Role.USER, provider, userInfo.providerId()));
    userProfileRepository.save(new UserProfile(
        user, uniqueNickname(userInfo.nickname(), username, userInfo.providerId()), null));
    log.info("소셜 신규 사용자 가입: username={}", username);
    return user;
  }

  // 소셜 닉네임이 없으면 username으로, 기존 닉네임과 충돌하면 providerId를 붙여 유일성 확보 (단계 7 정책)
  private String uniqueNickname(String socialNickname, String username, String providerId) {
    String base = (socialNickname == null || socialNickname.isBlank()) ? username : socialNickname;
    if (!userProfileRepository.existsByNickname(base)) {
      return base;
    }
    return base + "_" + providerId;
  }

  // ── 카카오 전용: 중첩 JSON을 한 단계씩 확인하며 내려간다 (동의 항목 없으면 각 단계가 null) ──

  private String kakaoEmail(Map<String, Object> attributes) {
    Object accountValue = attributes.get("kakao_account");
    if (accountValue == null) {
      return null;
    }
    Map<?, ?> account = (Map<?, ?>) accountValue;
    return (String) account.get("email");
  }

  private String kakaoNickname(Map<String, Object> attributes) {
    Object accountValue = attributes.get("kakao_account");
    if (accountValue == null) {
      return null;
    }
    Map<?, ?> account = (Map<?, ?>) accountValue;

    Object profileValue = account.get("profile");
    if (profileValue == null) {
      return null;
    }
    Map<?, ?> profile = (Map<?, ?>) profileValue;
    return (String) profile.get("nickname");
  }
}
