package com.example.board.auth;

import com.example.board.auth.dto.LoginRequest;
import com.example.board.auth.dto.SignupRequest;
import com.example.board.auth.dto.TokenPair;
import com.example.board.auth.jwt.JwtTokenProvider;
import com.example.board.auth.token.RefreshTokenStore;
import com.example.board.auth.token.TokenDenylist;
import com.example.board.global.exception.DuplicateException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.UnauthorizedException;
import com.example.board.profile.UserProfile;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import com.example.board.user.dto.UserResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final UserProfileRepository userProfileRepository;
  // 단계 15 처리에 의해 제거: RefreshTokenRepository(JPA) → RefreshTokenStore(Redis, TTL)
  private final RefreshTokenStore refreshTokenStore;
  private final TokenDenylist tokenDenylist;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider tokenProvider;
  private final AuthenticationManager authenticationManager;

  @Value("${jwt.access-token-validity-seconds}")
  private long accessTokenValiditySeconds;

  @Value("${jwt.refresh-token-validity-seconds}")
  private long refreshTokenValiditySeconds;

  // User와 UserProfile을 한 트랜잭션으로 생성 — 하나라도 실패하면 모두 롤백
  @Transactional
  public UserResponse signup(SignupRequest request) {
    if (userRepository.existsByUsername(request.username())) {
      throw new DuplicateException(ErrorCode.DUPLICATE_USERNAME);
    }
    if (userRepository.existsByEmail(request.email())) {
      throw new DuplicateException(ErrorCode.DUPLICATE_EMAIL);
    }
    if (userProfileRepository.existsByNickname(request.nickname())) {
      throw new DuplicateException(ErrorCode.NICKNAME_DUPLICATED);
    }

    User user = userRepository.save(new User(
        request.username(),
        request.email(),
        passwordEncoder.encode(request.password()),
        Role.USER));
    userProfileRepository.save(new UserProfile(user, request.nickname(), null));

    return UserResponse.from(user);
  }

  // 강의 포인트: 단계 3은 AuthenticationManager에 인증을 위임한다.
  // Spring이 CustomUserDetailsService로 사용자를 로딩하고 PasswordEncoder로 비밀번호를 검증한다.
  // username 미존재와 password 불일치를 같은 메시지(LOGIN_FAILED)로 응답한다 — user enumeration 방지.
  // access token은 username을 담은 stateless JWT(1시간). refresh token은 opaque UUID로 DB에 저장한다(stateful).
  // refresh token을 발급/교체해야 하므로 쓰기 트랜잭션이다(readOnly 아님).
  @Transactional
  public TokenPair login(LoginRequest request) {
    try {
      Authentication authentication = authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(request.username(), request.password()));
      CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
      // 토큰 생성까지만 책임진다. 본문(access)·쿠키(refresh) 전달은 컨트롤러가 결정.
      return createTokenPair(principal.getUsername(), principal.getId());
    } catch (AuthenticationException e) {
      throw new UnauthorizedException(ErrorCode.LOGIN_FAILED);
    }
  }

  // 단계 7: 인증 수단(로컬 password / 카카오 OAuth)과 무관하게 "인증이 끝난 사용자"에게 토큰 쌍을 발급한다.
  // KakaoOAuthService가 재사용한다 — 발급 경로가 같으므로 이후 reissue/logout 흐름도 완전히 동일하다.
  @Transactional
  public TokenPair issueTokenPair(User user) {
    return createTokenPair(user.getUsername(), user.getId());
  }

  private TokenPair createTokenPair(String username, Long userId) {
    String accessToken = tokenProvider.createToken(username);
    String refreshToken = issueRefreshToken(userId);
    return new TokenPair(
        accessToken, refreshToken, accessTokenValiditySeconds, refreshTokenValiditySeconds);
  }

  // 강의 포인트: refresh token으로는 새 access token만 재발급한다(stateful 검증).
  // refresh token 자체가 자격증명이므로 별도 인증 없이 호출된다(SecurityConfig에서 /auth/** 공개).
  // refresh token은 회전(rotation)하지 않고 만료 전까지 그대로 둔다 — 회전은 후속 주제.
  @Transactional(readOnly = true)
  public TokenPair reissue(String refreshToken) {
    // 단계 15: 만료 분기(isExpired → EXPIRED_REFRESH_TOKEN)가 소멸했다 —
    // TTL이 지나면 키 자체가 사라지므로 "없음 = 무효" 한 가지로 단순해진다.
    Long userId = refreshTokenStore.findUserId(refreshToken)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN));
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN));
    String newAccessToken = tokenProvider.createToken(user.getUsername());
    // access token만 새로 발급한다. refresh token은 회전하지 않고 기존 값을 그대로 돌려준다.
    return new TokenPair(
        newAccessToken, refreshToken, accessTokenValiditySeconds, refreshTokenValiditySeconds);
  }

  // 로그아웃 = refresh 폐기 + access "즉시" 폐기(단계 15).
  // 기존에는 access가 만료(최대 1시간)까지 유효했지만, 이제 jti를 남은 수명만큼 denylist에
  // 등록해 다음 요청부터 401이 된다. 두 삭제 모두 이미 없어도 조용히 통과(멱등).
  public void logout(String refreshToken, String accessToken) {
    if (refreshToken != null) {
      refreshTokenStore.deleteByToken(refreshToken);
    }
    if (accessToken != null && tokenProvider.validateToken(accessToken)) {
      tokenDenylist.deny(
          tokenProvider.getJti(accessToken), tokenProvider.getRemainingSeconds(accessToken));
    }
  }

  // opaque 랜덤 토큰(UUID)을 만들어 한 사용자당 하나로 저장한다 — 기존이 있으면 교체.
  // 만료는 저장소의 TTL이 관리한다(단계 15). 평문 UUID 저장은 강의 단순화 — 운영에선 해시 권장.
  private String issueRefreshToken(Long userId) {
    String token = UUID.randomUUID().toString();
    refreshTokenStore.save(userId, token, refreshTokenValiditySeconds);
    return token;
  }
}
