package com.example.board.auth.oauth;

import com.example.board.auth.AuthService;
import com.example.board.auth.dto.TokenPair;
import com.example.board.auth.oauth.dto.KakaoTokenResponse;
import com.example.board.auth.oauth.dto.KakaoUserResponse;
import com.example.board.profile.UserProfile;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.AuthProvider;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 카카오 인증 결과를 "우리 서비스의 사용자/토큰"으로 연결하는 비즈니스 로직.
// 흐름: 인가 코드 → (client) 카카오 토큰 → (client) 카카오 사용자 → find-or-create → 우리 JWT 발급.
// 강의 포인트: 카카오는 "이 사람이 카카오 회원 12345다"까지만 보증한다.
// 그 사람을 우리 users 테이블 어느 행에 연결할지(가입/로그인)는 전적으로 우리 책임이다.
@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoOAuthService {

  private static final String USERNAME_PREFIX = "kakao_";

  private final KakaoOAuthClient kakaoOAuthClient;
  private final UserRepository userRepository;
  private final UserProfileRepository userProfileRepository;
  private final PasswordEncoder passwordEncoder;
  private final AuthService authService;

  public String authorizeUrl(String state) {
    return kakaoOAuthClient.authorizeUrl(state);
  }

  // 인가 코드 하나로 로그인 전 과정을 처리하고 우리 서비스의 토큰 쌍을 돌려준다.
  // 신규 사용자면 User + UserProfile 생성까지 한 트랜잭션 — 실패 시 모두 롤백된다.
  @Transactional
  public TokenPair login(String code) {
    KakaoTokenResponse kakaoToken = kakaoOAuthClient.requestToken(code);
    KakaoUserResponse kakaoUser = kakaoOAuthClient.fetchUser(kakaoToken.accessToken());
    User user = findOrCreateUser(kakaoUser);
    // 로컬 로그인과 같은 발급 경로를 재사용한다 — 이후 reissue/logout 흐름이 단계 5와 동일해진다
    return authService.issueTokenPair(user);
  }

  // (provider, providerId)로 찾고 없으면 가입 — 소셜 로그인의 "로그인과 가입이 하나"인 지점
  private User findOrCreateUser(KakaoUserResponse kakaoUser) {
    String providerId = String.valueOf(kakaoUser.getId());
    return userRepository.findByProviderAndProviderId(AuthProvider.KAKAO, providerId)
        .orElseGet(() -> createUser(kakaoUser, providerId));
  }

  private User createUser(KakaoUserResponse kakaoUser, String providerId) {
    String username = USERNAME_PREFIX + providerId;

    // 이메일 동의 항목이 없으면 null, 기존 로컬 계정과 겹치면 unique 위반 —
    // 두 경우 모두 시스템 생성 대체 이메일로 채운다 (로컬-소셜 계정 연동은 후속 주제)
    String email = kakaoUser.getEmail();
    if (email == null || userRepository.existsByEmail(email)) {
      email = username + "@kakao.local";
    }

    // 소셜 사용자는 password 로그인이 불가능해야 한다 —
    // 아무도 모르는 랜덤 값을 해시해 저장한다 (NOT NULL 제약 충족 + 우연한 로그인 차단)
    String password = passwordEncoder.encode(UUID.randomUUID().toString());

    User user = userRepository.save(
        new User(username, email, password, Role.USER, AuthProvider.KAKAO, providerId));
    userProfileRepository.save(
        new UserProfile(user, uniqueNickname(kakaoUser.getNickname(), providerId), null));
    log.info("카카오 신규 사용자 가입: username={}", username);
    return user;
  }

  // 카카오 닉네임이 기존 닉네임과 충돌하면 카카오 회원번호를 붙여 유일성을 확보한다
  private String uniqueNickname(String kakaoNickname, String providerId) {
    String base = (kakaoNickname == null || kakaoNickname.isBlank())
        ? USERNAME_PREFIX + providerId
        : kakaoNickname;
    if (!userProfileRepository.existsByNickname(base)) {
      return base;
    }
    return base + "_" + providerId;
  }
}
