package com.example.board.auth.jwt;

import com.example.board.auth.CustomUserDetailsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

// 강의 포인트: Bearer 토큰을 검증해 인증 정보를 SecurityContext에 "심기"만 한다.
// 막지 않는 이유: 공개 엔드포인트가 있으므로, 인증/인가 판단은 뒤따르는 SecurityFilterChain이 맡는다.
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtTokenProvider tokenProvider;
  private final CustomUserDetailsService userDetailsService;

  // 요청당 한 번, 컨트롤러보다 먼저 서블릿 컨테이너가 호출한다 (OncePerRequestFilter)
  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    String token = resolveToken(request);
    if (token != null && tokenProvider.validateToken(token)) {
      // 토큰의 username으로 UserDetails를 로딩해 표준 Authentication을 만들어 컨텍스트에 저장한다
      String username = tokenProvider.getUsername(token);
      UserDetails userDetails = userDetailsService.loadUserByUsername(username);
      UsernamePasswordAuthenticationToken authToken =
          new UsernamePasswordAuthenticationToken(
              userDetails, null, userDetails.getAuthorities());
      authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
      SecurityContextHolder.getContext().setAuthentication(authToken);
    }
    filterChain.doFilter(request, response); // 토큰이 없거나 무효여도 막지 않고 통과
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
