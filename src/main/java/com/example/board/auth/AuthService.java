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
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider tokenProvider;
  private final AuthenticationManager authenticationManager;

  @Value("${jwt.access-token-validity-seconds}")
  private long accessTokenValiditySeconds;

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
  // 인증 성공 시 세션에 저장하지 않고, username을 담은 JWT를 발급해 클라이언트에 돌려준다(stateless).
  @Transactional(readOnly = true)
  public TokenResponse login(LoginRequest request) {
    try {
      Authentication authentication = authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(request.username(), request.password()));
      CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
      String accessToken = tokenProvider.createToken(principal.getUsername());
      return TokenResponse.bearer(accessToken, accessTokenValiditySeconds);
    } catch (AuthenticationException e) {
      throw new UnauthorizedException(ErrorCode.LOGIN_FAILED);
    }
  }
}
