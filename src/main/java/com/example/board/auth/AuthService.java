package com.example.board.auth;

import com.example.board.auth.dto.LoginRequest;
import com.example.board.auth.dto.SignupRequest;
import com.example.board.auth.dto.TokenResponse;
import com.example.board.auth.jwt.JwtTokenProvider;
import com.example.board.global.exception.DuplicateException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.UnauthorizedException;
import com.example.board.profile.UserProfile;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import com.example.board.user.dto.UserResponse;
import java.time.LocalDateTime;
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
  private final RefreshTokenRepository refreshTokenRepository;
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
  public TokenResponse login(LoginRequest request) {
    try {
      Authentication authentication = authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(request.username(), request.password()));
      CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
      String accessToken = tokenProvider.createToken(principal.getUsername());
      String refreshToken = issueRefreshToken(principal.getId());
      return TokenResponse.bearer(accessToken, refreshToken, accessTokenValiditySeconds);
    } catch (AuthenticationException e) {
      throw new UnauthorizedException(ErrorCode.LOGIN_FAILED);
    }
  }

  // 강의 포인트: refresh token으로는 새 access token만 재발급한다(stateful 검증).
  // refresh token 자체가 자격증명이므로 별도 인증 없이 호출된다(SecurityConfig에서 /auth/** 공개).
  // refresh token은 회전(rotation)하지 않고 만료 전까지 그대로 둔다 — 회전은 후속 주제.
  @Transactional
  public TokenResponse reissue(String refreshToken) {
    RefreshToken stored = refreshTokenRepository.findByToken(refreshToken)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN));
    if (stored.isExpired()) {
      refreshTokenRepository.delete(stored);
      throw new UnauthorizedException(ErrorCode.EXPIRED_REFRESH_TOKEN);
    }
    User user = userRepository.findById(stored.getUserId())
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN));
    String newAccessToken = tokenProvider.createToken(user.getUsername());
    return TokenResponse.bearer(newAccessToken, refreshToken, accessTokenValiditySeconds);
  }

  // 서버 측 refresh token을 삭제한다. 이미 없어도 예외 없이 통과(idempotent).
  @Transactional
  public void logout(String refreshToken) {
    refreshTokenRepository.findByToken(refreshToken)
        .ifPresent(refreshTokenRepository::delete);
  }

  // opaque 랜덤 토큰(UUID)을 만들어 한 사용자당 하나로 저장한다 — 기존이 있으면 교체, 없으면 새로 생성.
  // 평문 UUID 저장은 강의 단순화. 운영에선 해시 저장을 권장한다.
  private String issueRefreshToken(Long userId) {
    String token = UUID.randomUUID().toString();
    LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(refreshTokenValiditySeconds);
    refreshTokenRepository.findByUserId(userId)
        .ifPresentOrElse(
            existing -> existing.update(token, expiresAt),
            () -> refreshTokenRepository.save(new RefreshToken(userId, token, expiresAt)));
    return token;
  }
}
