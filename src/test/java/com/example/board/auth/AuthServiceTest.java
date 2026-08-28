package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.auth.token.InMemoryRefreshTokenStore;
import com.example.board.auth.token.InMemoryTokenDenylist;
import com.example.board.auth.token.RefreshTokenStore;
import com.example.board.auth.token.TokenDenylist;
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
import org.junit.jupiter.api.BeforeEach;
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

  // 단계 15: RefreshTokenRepository(JPA) → RefreshTokenStore/TokenDenylist (테스트에선 InMemory 대체)
  @Autowired
  RefreshTokenStore refreshTokenStore;

  @Autowired
  TokenDenylist tokenDenylist;

  // @Transactional 롤백은 인메모리 저장소를 되돌리지 못하므로 테스트마다 직접 비운다
  @BeforeEach
  void clearTokenStores() {
    ((InMemoryRefreshTokenStore) refreshTokenStore).clear();
    ((InMemoryTokenDenylist) tokenDenylist).clear();
  }

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
    // 단계 15: 저장소가 토큰→사용자 매핑을 보관한다 (만료는 TTL 관할이라 검사 항목이 아님)
    assertThat(refreshTokenStore.findUserId(tokens.refreshToken())).contains(userId);
  }

  @Test
  void should_replaceRefreshToken_whenLoginAgain() {
    authService.signup(new SignupRequest("tester1", "tester1@example.com", "password123", "테스터"));
    Long userId = userRepository.findByUsername("tester1").orElseThrow().getId();

    String first = authService.login(new LoginRequest("tester1", "password123")).refreshToken();
    String second = authService.login(new LoginRequest("tester1", "password123")).refreshToken();

    assertThat(second).isNotEqualTo(first);
    assertThat(refreshTokenStore.findUserId(first)).isEmpty();     // 옛 토큰은 즉시 무효(사용자당 1개)
    assertThat(refreshTokenStore.findUserId(second)).contains(userId);
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

  // 단계 15 처리에 의해 제거: should_throwExpiredRefreshToken — TTL이 만료를 관할하므로
  // "만료된 토큰 = 키 부재 = INVALID_REFRESH_TOKEN"으로 단일화됐다(위 not-found 테스트가 그 경로).

  @Test
  void should_deleteRefreshAndDenyAccess_whenLogout() {
    authService.signup(new SignupRequest("tester1", "tester1@example.com", "password123", "테스터"));
    TokenPair tokens = authService.login(new LoginRequest("tester1", "password123"));

    authService.logout(tokens.refreshToken(), tokens.accessToken());

    assertThat(refreshTokenStore.findUserId(tokens.refreshToken())).isEmpty();
    // 단계 15 핵심: access token이 만료 전이라도 "즉시" 폐기 목록에 오른다
    assertThat(tokenDenylist.isDenied(tokenProvider.getJti(tokens.accessToken()))).isTrue();
  }

  @Test
  void should_notThrow_whenLogoutWithUnknownToken() {
    authService.logout("no-such-token", null);
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
