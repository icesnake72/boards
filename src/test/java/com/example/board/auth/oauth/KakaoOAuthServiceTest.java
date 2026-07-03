package com.example.board.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.example.board.auth.RefreshTokenRepository;
import com.example.board.auth.dto.TokenPair;
import com.example.board.auth.jwt.JwtTokenProvider;
import com.example.board.auth.oauth.dto.KakaoTokenResponse;
import com.example.board.auth.oauth.dto.KakaoUserResponse;
import com.example.board.profile.UserProfile;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.AuthProvider;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

// 카카오 서버와의 통신(KakaoOAuthClient)만 mock으로 대체하고,
// find-or-create부터 토큰 발급까지는 실제 빈/DB(H2)로 검증하는 통합 테스트.
@SpringBootTest
@Transactional
class KakaoOAuthServiceTest {

  @Autowired
  KakaoOAuthService kakaoOAuthService;

  @Autowired
  UserRepository userRepository;

  @Autowired
  UserProfileRepository userProfileRepository;

  @Autowired
  RefreshTokenRepository refreshTokenRepository;

  @Autowired
  PasswordEncoder passwordEncoder;

  @Autowired
  JwtTokenProvider tokenProvider;

  @MockitoBean
  KakaoOAuthClient kakaoOAuthClient;

  private static final long KAKAO_ID = 4242424242L;

  private void stubKakao(String nickname, String email) {
    given(kakaoOAuthClient.requestToken("test-code")).willReturn(
        new KakaoTokenResponse("kakao-access", "bearer", "kakao-refresh", 21599L, "profile"));
    given(kakaoOAuthClient.fetchUser("kakao-access")).willReturn(
        new KakaoUserResponse(KAKAO_ID, new KakaoUserResponse.KakaoAccount(
            email, new KakaoUserResponse.KakaoAccount.Profile(nickname))));
  }

  @Test
  void should_createUserAndProfile_whenFirstKakaoLogin() {
    stubKakao("카카오테스터", "kakao-user@example.com");

    TokenPair tokens = kakaoOAuthService.login("test-code");

    User user = userRepository
        .findByProviderAndProviderId(AuthProvider.KAKAO, String.valueOf(KAKAO_ID))
        .orElseThrow();
    assertThat(user.getUsername()).isEqualTo("kakao_" + KAKAO_ID);
    assertThat(user.getEmail()).isEqualTo("kakao-user@example.com");
    assertThat(user.getRole()).isEqualTo(Role.USER);

    UserProfile profile = userProfileRepository.findByUserId(user.getId()).orElseThrow();
    assertThat(profile.getNickname()).isEqualTo("카카오테스터");

    // 발급된 access token은 우리 서비스의 JWT다 — subject가 username이어야 한다
    assertThat(tokenProvider.getUsername(tokens.accessToken())).isEqualTo(user.getUsername());
    // refresh token도 로컬 로그인과 같은 경로로 DB에 저장된다
    assertThat(refreshTokenRepository.findByToken(tokens.refreshToken())).isPresent();
  }

  @Test
  void should_reuseExistingUser_whenSecondKakaoLogin() {
    stubKakao("카카오테스터", "kakao-user@example.com");
    long before;

    kakaoOAuthService.login("test-code");
    before = userRepository.count();
    TokenPair second = kakaoOAuthService.login("test-code");

    assertThat(userRepository.count()).isEqualTo(before);
    assertThat(second.accessToken()).isNotBlank();
  }

  @Test
  void should_useFallbackEmail_whenEmailNotProvided() {
    stubKakao("카카오테스터", null);

    kakaoOAuthService.login("test-code");

    User user = userRepository
        .findByProviderAndProviderId(AuthProvider.KAKAO, String.valueOf(KAKAO_ID))
        .orElseThrow();
    assertThat(user.getEmail()).isEqualTo("kakao_" + KAKAO_ID + "@kakao.local");
  }

  @Test
  void should_useFallbackEmail_whenEmailAlreadyUsedByLocalUser() {
    User local = userRepository.save(new User(
        "localuser", "shared@example.com", passwordEncoder.encode("password123"), Role.USER));
    userProfileRepository.save(new UserProfile(local, "로컬유저", null));
    stubKakao("카카오테스터", "shared@example.com");

    kakaoOAuthService.login("test-code");

    User kakaoUser = userRepository
        .findByProviderAndProviderId(AuthProvider.KAKAO, String.valueOf(KAKAO_ID))
        .orElseThrow();
    assertThat(kakaoUser.getEmail()).isEqualTo("kakao_" + KAKAO_ID + "@kakao.local");
    // 기존 로컬 계정은 그대로다 — 계정 연동이 아니라 별도 사용자로 생성된다
    assertThat(kakaoUser.getId()).isNotEqualTo(local.getId());
  }

  @Test
  void should_suffixNickname_whenNicknameAlreadyExists() {
    User local = userRepository.save(new User(
        "localuser", "local@example.com", passwordEncoder.encode("password123"), Role.USER));
    userProfileRepository.save(new UserProfile(local, "중복닉네임", null));
    stubKakao("중복닉네임", null);

    kakaoOAuthService.login("test-code");

    User kakaoUser = userRepository
        .findByProviderAndProviderId(AuthProvider.KAKAO, String.valueOf(KAKAO_ID))
        .orElseThrow();
    UserProfile profile = userProfileRepository.findByUserId(kakaoUser.getId()).orElseThrow();
    assertThat(profile.getNickname()).isEqualTo("중복닉네임_" + KAKAO_ID);
  }
}
