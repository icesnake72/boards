package com.example.board.global.config;

import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

// 미인증 사용자가 보호 자원에 접근할 때(401). 우리 ErrorResponse JSON으로 응답한다.
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    writeError(response, ErrorCode.LOGIN_REQUIRED);
  }

  private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
    objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
  }
}
