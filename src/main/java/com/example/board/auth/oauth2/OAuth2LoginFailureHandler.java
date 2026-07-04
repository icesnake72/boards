package com.example.board.auth.oauth2;

import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

// 단계 8: OAuth 로그인 실패(동의 거부, state 불일치, 토큰 교환 실패 등)를 401 JSON으로.
// 기본 동작은 /login?error 리다이렉트 — 화면 없는 REST API에는 맞지 않아 교체한다.
// 세부 사유는 로그로만 남기고 밖으로는 한 코드로 응답한다 (단계 7과 같은 정책).
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginFailureHandler implements AuthenticationFailureHandler {

  private final ObjectMapper objectMapper;

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException exception) throws IOException {
    // uri·쿼리까지 남겨야 "콜백을 파라미터 없이 직접 호출" 같은 원인을 로그만으로 구분할 수 있다
    log.warn("OAuth 로그인 실패: {} (uri={}, query={})",
        exception.getMessage(), request.getRequestURI(), request.getQueryString());
    ErrorCode errorCode = ErrorCode.OAUTH_LOGIN_FAILED;
    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
    objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
  }
}
