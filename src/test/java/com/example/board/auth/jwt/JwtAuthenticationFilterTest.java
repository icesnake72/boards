package com.example.board.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.board.auth.CustomUserDetailsService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.servlet.HandlerExceptionResolver;

// 단계 13 이후 보강: 필터 단계 예외가 "인증 실패(401)"와 "내부 오류(500)"로 올바르게 갈리는지 검증.
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

  @Mock JwtTokenProvider tokenProvider;
  @Mock CustomUserDetailsService userDetailsService;
  @Mock HandlerExceptionResolver handlerExceptionResolver;
  @Mock FilterChain filterChain;

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private JwtAuthenticationFilter filter() {
    return new JwtAuthenticationFilter(tokenProvider, userDetailsService, handlerExceptionResolver);
  }

  private MockHttpServletRequest withBearer() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer some-token");
    return request;
  }

  // 유효 토큰인데 사용자가 삭제된 경우 — 인증 실패로 통과시켜 뒷단 entryPoint(401)가 처리하게 한다.
  @Test
  void should_passThroughWithoutAuth_whenAuthenticationExceptionInFilter() throws Exception {
    MockHttpServletRequest request = withBearer();
    MockHttpServletResponse response = new MockHttpServletResponse();
    given(tokenProvider.validateToken(any())).willReturn(true);
    given(tokenProvider.getUsername(any())).willReturn("gone");
    given(userDetailsService.loadUserByUsername("gone"))
        .willThrow(new UsernameNotFoundException("no such user"));

    filter().doFilter(request, response, filterChain);

    // 컨텍스트는 비어 있고(미인증), 체인은 계속 진행 → 뒷단에서 401 결정
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain).doFilter(request, response);
    verify(handlerExceptionResolver, never()).resolveException(any(), any(), any(), any());
  }

  // 예상치 못한 내부 오류 — 401로 둔갑시키지 않고 resolver로 위임(→ GlobalExceptionHandler 500).
  @Test
  void should_delegateToResolver_whenInternalErrorInFilter() throws Exception {
    MockHttpServletRequest request = withBearer();
    MockHttpServletResponse response = new MockHttpServletResponse();
    given(tokenProvider.validateToken(any())).willReturn(true);
    given(tokenProvider.getUsername(any())).willReturn("user");
    given(userDetailsService.loadUserByUsername("user"))
        .willThrow(new RuntimeException("DB down"));

    filter().doFilter(request, response, filterChain);

    // 내부 오류는 resolver로 위임되고, 체인은 진행되지 않는다(위임했으므로)
    verify(handlerExceptionResolver)
        .resolveException(eq(request), eq(response), isNull(), any(RuntimeException.class));
    verify(filterChain, never()).doFilter(any(), any());
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  // 무효 토큰(validateToken false) — 컨텍스트 안 채우고 통과(기존 동작 유지).
  @Test
  void should_passThrough_whenTokenInvalid() throws Exception {
    MockHttpServletRequest request = withBearer();
    MockHttpServletResponse response = new MockHttpServletResponse();
    given(tokenProvider.validateToken(any())).willReturn(false);

    filter().doFilter(request, response, filterChain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain).doFilter(request, response);
    verify(handlerExceptionResolver, never()).resolveException(any(), any(), any(), any());
  }
}
