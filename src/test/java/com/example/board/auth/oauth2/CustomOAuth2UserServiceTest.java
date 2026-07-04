package com.example.board.auth.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.board.profile.UserProfile;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.AuthProvider;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

// 카카오 attributes(중첩 Map) → find-or-create 정책 검증.
// 단계 7 KakaoOAuthServiceTest와 같은 케이스 — 로직이 표준 부품으로 이사해도 정책은 동일해야 한다.
@SpringBootTest
@Transactional
class CustomOAuth2UserServiceTest {

  @Autowired
  CustomOAuth2UserService customOAuth2UserService;

  @Autowired
  UserRepository userRepository;

  @Autowired
  UserProfileRepository userProfileRepository;

  @Autowired
  PasswordEncoder passwordEncoder;

  private static final long KAKAO_ID = 5353535353L;

  // 카카오 /v2/user/me 응답과 같은 모양의 attributes.
  // email 미동의면 kakao_account에 email 키 자체가 없다 — 그 형태 그대로 만든다.
  private Map<String, Object> kakaoAttributesWithoutEmail(String nickname) {
    return Map.of(
        "id", KAKAO_ID,
        "kakao_account", Map.of(
            "profile", Map.of("nickname", nickname)));
  }

  @Test
  void should_createUserAndProfile_whenFirstLogin() {
    User user = customOAuth2UserService.upsertUser("kakao",
        Map.of(
            "id", KAKAO_ID,
            "kakao_account", Map.of(
                "email", "standard@example.com",
                "profile", Map.of("nickname", "표준테스터"))));

    assertThat(user.getUsername()).isEqualTo("kakao_" + KAKAO_ID);
    assertThat(user.getEmail()).isEqualTo("standard@example.com");
    assertThat(user.getProvider()).isEqualTo(AuthProvider.KAKAO);
    assertThat(user.getProviderId()).isEqualTo(String.valueOf(KAKAO_ID));
    UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
    assertThat(profile.getNickname()).isEqualTo("표준테스터");
  }

  @Test
  void should_reuseExistingUser_whenSecondLogin() {
    User first = customOAuth2UserService.upsertUser("kakao", kakaoAttributesWithoutEmail("표준테스터"));
    long count = userRepository.count();

    User second = customOAuth2UserService.upsertUser("kakao", kakaoAttributesWithoutEmail("표준테스터"));

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(userRepository.count()).isEqualTo(count);
  }

  @Test
  void should_useFallbackEmail_whenEmailNotProvided() {
    User user = customOAuth2UserService.upsertUser("kakao", kakaoAttributesWithoutEmail("표준테스터"));

    assertThat(user.getEmail()).isEqualTo("kakao_" + KAKAO_ID + "@kakao.local");
  }

  @Test
  void should_suffixNickname_whenNicknameAlreadyExists() {
    User local = userRepository.save(new User(
        "localuser2", "local2@example.com", passwordEncoder.encode("password123"), Role.USER));
    userProfileRepository.save(new UserProfile(local, "겹치는닉", null));

    User kakaoUser = customOAuth2UserService.upsertUser("kakao",
        Map.of(
            "id", KAKAO_ID,
            "kakao_account", Map.of("profile", Map.of("nickname", "겹치는닉"))));

    UserProfile profile = userProfileRepository.findByUserId(kakaoUser.getId()).orElseThrow();
    assertThat(profile.getNickname()).isEqualTo("겹치는닉_" + KAKAO_ID);
  }

  // ── 구글 (단계 8 확장) — 평면 attributes: sub / email / name ──

  private static final String GOOGLE_SUB = "107690741234567890123";

  @Test
  void should_createGoogleUser_whenFirstLogin() {
    User user = customOAuth2UserService.upsertUser("google",
        Map.of("sub", GOOGLE_SUB, "email", "guser@gmail.com", "name", "구글테스터"));

    assertThat(user.getUsername()).isEqualTo("google_" + GOOGLE_SUB);
    assertThat(user.getEmail()).isEqualTo("guser@gmail.com");
    assertThat(user.getProvider()).isEqualTo(AuthProvider.GOOGLE);
    assertThat(user.getProviderId()).isEqualTo(GOOGLE_SUB);
    UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
    assertThat(profile.getNickname()).isEqualTo("구글테스터");
  }

  @Test
  void should_useFallbackEmail_whenGoogleEmailAlreadyUsed() {
    // 같은 이메일의 카카오 사용자가 이미 있는 상황 — 실제로 흔하다 (같은 사람이 두 소셜로 가입)
    customOAuth2UserService.upsertUser("kakao",
        Map.of(
            "id", KAKAO_ID,
            "kakao_account", Map.of(
                "email", "same@gmail.com",
                "profile", Map.of("nickname", "카카오쪽"))));

    User googleUser = customOAuth2UserService.upsertUser("google",
        Map.of("sub", GOOGLE_SUB, "email", "same@gmail.com", "name", "구글쪽"));

    // 이메일이 겹치면 대체 이메일로 별도 계정 생성 — 자동 연동하지 않는다(계정 탈취 방지)
    assertThat(googleUser.getEmail()).isEqualTo("google_" + GOOGLE_SUB + "@google.local");
    assertThat(googleUser.getProvider()).isEqualTo(AuthProvider.GOOGLE);
  }

  @Test
  void should_keepProvidersSeparate_whenSameProviderIdValue() {
    // (provider, providerId) 복합키 검증 — 다른 제공자면 providerId가 같아도 다른 사용자다
    customOAuth2UserService.upsertUser("kakao", kakaoAttributesWithoutEmail("A"));
    User google = customOAuth2UserService.upsertUser("google",
        Map.of("sub", String.valueOf(KAKAO_ID), "email", "g2@gmail.com", "name", "B"));

    assertThat(google.getUsername()).isEqualTo("google_" + KAKAO_ID);
    assertThat(userRepository.findByProviderAndProviderId(
        AuthProvider.KAKAO, String.valueOf(KAKAO_ID))).isPresent();
    assertThat(userRepository.findByProviderAndProviderId(
        AuthProvider.GOOGLE, String.valueOf(KAKAO_ID))).isPresent();
  }
}
