package com.example.board.auth;

import com.example.board.auth.dto.LoginRequest;
import com.example.board.auth.dto.SignupRequest;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final UserProfileRepository userProfileRepository;
  private final PasswordEncoder passwordEncoder;

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

  // username 미존재와 password 불일치를 같은 메시지(LOGIN_FAILED)로 응답한다
  // — 어떤 계정이 존재하는지 노출하지 않기 위함 (user enumeration 방지)
  @Transactional(readOnly = true)
  public UserResponse login(LoginRequest request) {
    User user = userRepository.findByUsername(request.username())
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.LOGIN_FAILED));

    if (!passwordEncoder.matches(request.password(), user.getPassword())) {
      throw new UnauthorizedException(ErrorCode.LOGIN_FAILED);
    }
    return UserResponse.from(user);
  }
}
