package com.example.board.auth.jwt;

import com.example.board.auth.AuthConst;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

// Authorization: Bearer <token>를 해석해 userId를 request attribute에 "심기"만 한다.
// 막지 않는 이유: 공개 엔드포인트가 있으므로, 인증 필요 여부는 @LoginUserId Resolver가 401로 판단한다.
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtTokenProvider tokenProvider;

  // 요청당 한 번, 컨트롤러보다 먼저 서블릿 컨테이너가 호출한다 (OncePerRequestFilter)
  @Override
  protected void doFilterInternal(
    @NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    String token = resolveToken(request);                  // 헤더에서 토큰 추출
    if (token != null && tokenProvider.validateToken(token)) {
      // 유효한 토큰이면 userId를 request에 심어둔다 → 이후 @LoginUserId Resolver가 읽는다
      request.setAttribute(AuthConst.LOGIN_USER_ID, tokenProvider.getUserId(token));
    }
    filterChain.doFilter(request, response);                // 막지 않고 다음 필터/컨트롤러로 통과
  }

  // Authorization 헤더에서 "Bearer " 접두어를 떼고 토큰 문자열만 반환 (없으면 null)
  private String resolveToken(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
      return header.substring(BEARER_PREFIX.length());
    }
    return null;
  }
}
