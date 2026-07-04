package com.example.board.auth.oauth2;

import com.example.board.profile.UserProfile;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.AuthProvider;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
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
// super.loadUser()가 단계 7의 KakaoOAuthClient.fetchUser(카카오 /v2/user/me 호출)를 대신한다.
// 우리가 추가하는 것은 그 결과를 우리 users 테이블에 연결하는 find-or-create뿐 —
// 단계 7 KakaoOAuthService의 로직이 그대로 이사 왔다.
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

  private static final String USERNAME_PREFIX = "kakao_";

  private final UserRepository userRepository;
  private final UserProfileRepository userProfileRepository;
  private final PasswordEncoder passwordEncoder;

  @Override
  @Transactional
  public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
    // 토큰 교환은 이미 라이브러리가 끝냈고, 여기서 카카오 사용자 정보를 조회한다
    OAuth2User oauth2User = super.loadUser(userRequest);
    upsertUser(oauth2User.getAttributes());
    // principal은 표준 타입 그대로 반환 — SuccessHandler가 getName()(카카오 회원번호)으로 우리 사용자를 찾는다
    return oauth2User;
  }

  // find-or-create — (provider, providerId)로 찾고 없으면 가입 (단계 7과 동일한 정책)
  User upsertUser(Map<String, Object> attributes) {
    String providerId = String.valueOf(attributes.get("id"));
    return userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, providerId)
        .orElseGet(() -> createUser(providerId, email(attributes), nickname(attributes)));
  }

  private User createUser(String providerId, String kakaoEmail, String kakaoNickname) {
    String username = USERNAME_PREFIX + providerId;

    // 이메일 미동의(null)이거나 기존 계정과 겹치면 대체 이메일 (계정 연동은 후속 주제)
    String email = kakaoEmail;
    if (email == null || userRepository.existsByEmail(email)) {
      email = username + "@kakao.local";
    }

    // 소셜 사용자는 password 로그인 불가 — 아무도 모르는 랜덤 값을 해시해 저장
    String password = passwordEncoder.encode(UUID.randomUUID().toString());

    User user = userRepository.save(
        new User(username, email, password, Role.USER, AuthProvider.KAKAO, providerId));
    userProfileRepository.save(
        new UserProfile(user, uniqueNickname(kakaoNickname, providerId), null));
    log.info("카카오 신규 사용자 가입(표준 경로): username={}", username);
    return user;
  }

  private String uniqueNickname(String kakaoNickname, String providerId) {
    String base = (kakaoNickname == null || kakaoNickname.isBlank())
        ? USERNAME_PREFIX + providerId
        : kakaoNickname;
    if (!userProfileRepository.existsByNickname(base)) {
      return base;
    }
    return base + "_" + providerId;
  }

  // 카카오 attributes는 단계 7 DTO가 받던 것과 같은 중첩 JSON이다.
  // 동의 항목이 없으면 각 단계의 값이 null일 수 있어, 한 단계씩 확인하며 내려간다.
  private String email(Map<String, Object> attributes) {
    Object accountValue = attributes.get("kakao_account");
    if (accountValue == null) {
      return null;
    }
    Map<?, ?> account = (Map<?, ?>) accountValue;
    return (String) account.get("email");
  }

  private String nickname(Map<String, Object> attributes) {
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
