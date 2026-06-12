package com.example.board.auth;

import com.example.board.auth.dto.LoginRequest;
import com.example.board.auth.dto.SignupRequest;
import com.example.board.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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

  // 세션 로그인 흐름 (강의 포인트):
  // 1. 서버가 HttpSession을 생성하고 userId를 저장한다
  // 2. 응답에 JSESSIONID 쿠키가 발급된다
  // 3. 이후 브라우저는 매 요청마다 이 쿠키를 보내고, 서버는 쿠키로 세션을 찾아 사용자를 식별한다
  @PostMapping("/login")
  public UserResponse login(@Valid @RequestBody LoginRequest request, HttpSession session) {
    UserResponse user = authService.login(request);
    session.setAttribute(SessionConst.LOGIN_USER_ID, user.id());
    return user;
  }

  // 세션을 무효화하면 서버 측 세션 데이터가 삭제되어 JSESSIONID 쿠키가 남아 있어도 무용지물이 된다
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
  }
}
