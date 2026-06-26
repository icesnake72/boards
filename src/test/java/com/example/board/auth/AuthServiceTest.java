package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.auth.dto.LoginRequest;
import com.example.board.auth.dto.SignupRequest;
import com.example.board.auth.dto.TokenPair;
import com.example.board.auth.jwt.JwtTokenProvider;
import com.example.board.global.exception.DuplicateException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.UnauthorizedException;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import com.example.board.user.dto.UserResponse;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AuthServiceTest {

  @Autowired
  AuthService authService;

  @Autowired
  UserRepository userRepository;

  @Autowired
  UserProfileRepository userProfileRepository;

  @Autowired
  PasswordEncoder passwordEncoder;

  @Autowired
  JwtTokenProvider tokenProvider;

  @Autowired
  RefreshTokenRepository refreshTokenRepository;

  @Test
  void should_createUserAndProfile_whenSignup() {
    SignupRequest request =
        new SignupRequest("tester1", "tester1@example.com", "password123", "테스터");

    UserResponse response = authService.signup(request);

    assertThat(response.username()).isEqualTo("tester1");
    User user = userRepository.findByUsername("tester1").orElseThrow();
    assertThat(user.getPassword()).isNotEqualTo("password123");
    assertThat(passwordEncoder.matches("password123", user.getPassword())).isTrue();
    assertThat(userProfileRepository.findByUserId(user.getId())).isPresent();
  }

  @Test
  void should_throwDuplicateException_whenUsernameExists() {
    authService.signup(new SignupRequest("tester1", "tester1@example.com", "password123", "테스터"));

    assertThatThrownBy(() -> authService.signup(
        new SignupRequest("tester1", "other@example.com", "password123", "다른닉네임")))
        .isInstanceOf(DuplicateException.class);
  }

  @Test
  void should_throwDuplicateException_whenEmailExists() {
    authService.signup(new SignupRequest("tester1", "tester1@example.com", "password123", "테스터"));

    assertThatThrownBy(() -> authService.signup(
        new SignupRequest("tester2", "tester1@example.com", "password123", "다른닉네임")))
        .isInstanceOf(DuplicateException.class);
  }

  @Test
  void should_throwDuplicateException_whenNicknameExists() {
    authService.signup(new SignupRequest("tester1", "tester1@example.com", "password123", "테스터"));

    assertThatThrownBy(() -> authService.signup(
        new SignupRequest("tester2", "tester2@example.com", "password123", "테스터")))
        .isInstanceOf(DuplicateException.class);
  }

  @Test
  void should_issueValidJwt_whenLoginSucceeds() {
    authService.signup(new SignupRequest("tester1", "tester1@example.com", "password123", "테스터"));

    TokenPair tokens = authService.login(new LoginRequest("tester1", "password123"));

    assertThat(tokens.accessToken()).isNotBlank();
    assertThat(tokenProvider.validateToken(tokens.accessToken())).isTrue();
    assertThat(tokenProvider.getUsername(tokens.accessToken())).isEqualTo("tester1");
  }

  @Test
  void should_issueAndStoreRefreshToken_whenLoginSucceeds() {
    authService.signup(new SignupRequest("tester1", "tester1@example.com", "password123", "테스터"));
    Long userId = userRepository.findByUsername("tester1").orElseThrow().getId();

    TokenPair tokens = authService.login(new LoginRequest("tester1", "password123"));

    assertThat(tokens.refreshToken()).isNotBlank();
    RefreshToken stored = refreshTokenRepository.findByUserId(userId).orElseThrow();
    assertThat(stored.getToken()).isEqualTo(tokens.refreshToken());
    assertThat(stored.isExpired()).isFalse();
  }

  @Test
  void should_replaceRefreshToken_whenLoginAgain() {
    authService.signup(new SignupRequest("tester1", "tester1@example.com", "password123", "테스터"));
    Long userId = userRepository.findByUsername("tester1").orElseThrow().getId();

    String first = authService.login(new LoginRequest("tester1", "password123")).refreshToken();
    String second = authService.login(new LoginRequest("tester1", "password123")).refreshToken();

    assertThat(second).isNotEqualTo(first);
    assertThat(refreshTokenRepository.findByToken(first)).isEmpty();
    RefreshToken stored = refreshTokenRepository.findByUserId(userId).orElseThrow();
    assertThat(stored.getToken()).isEqualTo(second);
  }

  @Test
  void should_reissueNewAccessTokenKeepingRefresh_whenRefreshTokenValid() {
    authService.signup(new SignupRequest("tester1", "tester1@example.com", "password123", "테스터"));
    TokenPair login = authService.login(new LoginRequest("tester1", "password123"));

    TokenPair reissued = authService.reissue(login.refreshToken());

    assertThat(reissued.refreshToken()).isEqualTo(login.refreshToken());
    assertThat(tokenProvider.validateToken(reissued.accessToken())).isTrue();
    assertThat(tokenProvider.getUsername(reissued.accessToken())).isEqualTo("tester1");
  }

  @Test
  void should_throwInvalidRefreshToken_whenRefreshTokenNotFound() {
    assertThatThrownBy(() -> authService.reissue("no-such-token"))
        .isInstanceOf(UnauthorizedException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REFRESH_TOKEN);
  }

  @Test
  void should_throwExpiredRefreshToken_whenRefreshTokenExpired() {
    User user = userRepository.save(new User(
        "tester1", "tester1@example.com", passwordEncoder.encode("password123"), Role.USER));
    String expiredToken = UUID.randomUUID().toString();
    refreshTokenRepository.save(
        new RefreshToken(user.getId(), expiredToken, LocalDateTime.now().minusSeconds(1)));

    assertThatThrownBy(() -> authService.reissue(expiredToken))
        .isInstanceOf(UnauthorizedException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXPIRED_REFRESH_TOKEN);
    assertThat(refreshTokenRepository.findByToken(expiredToken)).isEmpty();
  }

  @Test
  void should_deleteRefreshToken_whenLogout() {
    authService.signup(new SignupRequest("tester1", "tester1@example.com", "password123", "테스터"));
    String refreshToken =
        authService.login(new LoginRequest("tester1", "password123")).refreshToken();

    authService.logout(refreshToken);

    assertThat(refreshTokenRepository.findByToken(refreshToken)).isEmpty();
  }

  @Test
  void should_notThrow_whenLogoutWithUnknownToken() {
    authService.logout("no-such-token");
  }

  @Test
  void should_throwUnauthorizedException_whenPasswordMismatch() {
    authService.signup(new SignupRequest("tester1", "tester1@example.com", "password123", "테스터"));

    assertThatThrownBy(() -> authService.login(new LoginRequest("tester1", "wrong-password")))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void should_throwUnauthorizedException_whenUsernameNotFound() {
    assertThatThrownBy(() -> authService.login(new LoginRequest("no-such-user", "password123")))
        .isInstanceOf(UnauthorizedException.class);
  }
}
