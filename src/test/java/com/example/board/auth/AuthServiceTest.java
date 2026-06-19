package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.auth.dto.LoginRequest;
import com.example.board.auth.dto.SignupRequest;
import com.example.board.auth.dto.TokenResponse;
import com.example.board.auth.jwt.JwtTokenProvider;
import com.example.board.global.exception.DuplicateException;
import com.example.board.global.exception.UnauthorizedException;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import com.example.board.user.dto.UserResponse;
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
    UserResponse signedUp =
        authService.signup(new SignupRequest("tester1", "tester1@example.com", "password123", "테스터"));

    TokenResponse response = authService.login(new LoginRequest("tester1", "password123"));

    assertThat(response.accessToken()).isNotBlank();
    assertThat(response.tokenType()).isEqualTo("Bearer");
    assertThat(tokenProvider.getUserId(response.accessToken())).isEqualTo(signedUp.id());
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
