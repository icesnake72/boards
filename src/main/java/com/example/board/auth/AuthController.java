package com.example.board.auth;

import com.example.board.auth.dto.LoginRequest;
import com.example.board.auth.dto.SignupRequest;
import com.example.board.auth.dto.TokenResponse;
import com.example.board.user.dto.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  @PostMapping("/signup")
  @ResponseStatus(HttpStatus.CREATED)
  public UserResponse signup(@Valid @RequestBody SignupRequest request) {
    return authService.signup(request);
  }

  // JWT 로그인 흐름 (강의 포인트):
  // 1. 서버는 username/password를 검증하고 userId를 담은 access token(JWT)을 발급한다
  // 2. 서버는 어떤 로그인 상태도 저장하지 않는다(stateless) — 세션도 JSESSIONID 쿠키도 없다
  // 3. 이후 클라이언트는 매 요청 Authorization: Bearer <token> 헤더로 자신을 증명한다
  @PostMapping("/login")
  public TokenResponse login(@Valid @RequestBody LoginRequest request) {
    return authService.login(request);
  }

  // stateless JWT는 서버가 상태를 갖지 않으므로 서버 측 무효화가 없다.
  // 로그아웃 = 클라이언트가 보관 중인 토큰을 폐기하는 것. 서버는 할 일이 없으므로 204만 반환한다.
  // (만료 전 강제 무효화가 필요하면 토큰 블랙리스트가 필요 — 다음 단계 주제)
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout() {
  }
}
