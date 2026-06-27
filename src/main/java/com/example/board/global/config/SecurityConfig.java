package com.example.board.global.config;

import com.example.board.auth.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// 강의 단계 3 — Spring Security 표준(UserDetailsService + 선언적 인가).
// JwtAuthenticationFilter가 Bearer 토큰을 검증해 SecurityContext에 Authentication을 채우면,
// 인증/인가는 authorizeHttpRequests 규칙이 선언적으로 판단한다. 서버는 세션을 만들지 않는다(STATELESS).
// 강의 단계 6 — 메서드 보안(@EnableMethodSecurity, prePostEnabled 기본 true).
// 인가를 URL 규칙에서 메서드 레벨 @PreAuthorize로 옮긴다.
// (1) 역할 기반(hasRole) → Board 컨트롤러 메서드, (2) 자원 소유권(작성자만) → @postSecurity 커스텀 빈.
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;
  private final RestAuthenticationEntryPoint authenticationEntryPoint;
  private final RestAccessDeniedHandler accessDeniedHandler;

  // BCrypt: 단방향 해시 + salt 자동 포함. 비밀번호 저장의 표준.
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  // Spring이 CustomUserDetailsService + PasswordEncoder로 DaoAuthenticationProvider를 자동 구성한다.
  // AuthService.login에서 username/password 인증에 사용.
  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
      throws Exception {
    return configuration.getAuthenticationManager();
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        // CSRF 보호 끔 — 쿠키 세션이 아닌 토큰 인증이라 불필요 (REST API)
        .csrf(AbstractHttpConfigurer::disable)
        // 폼 로그인 화면 끔 — 로그인은 /auth/login JSON API로 직접 처리
        .formLogin(AbstractHttpConfigurer::disable)
        // HTTP Basic 인증 끔 — Bearer 토큰만 사용
        .httpBasic(AbstractHttpConfigurer::disable)
        // 세션을 만들지 않음(stateless) — 상태는 토큰이 들고 다닌다
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            // 공개: 인증/회원가입(로그아웃 포함 — stateless라 인증 불필요), 게시판·게시글 조회
            .requestMatchers("/api/v1/auth/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/boards/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/posts/**").permitAll()
            // /me는 인증 필요 — 타인 프로필(/profiles/{userId})보다 먼저 매칭해야 한다
            .requestMatchers("/api/v1/profiles/me").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/v1/profiles/*").permitAll()
            // 단계 6: board 생성/수정/삭제의 ADMIN 인가는 BoardController의 @PreAuthorize("hasRole('ADMIN')")로 이동.
            // 공개 GET 규칙과 anyRequest().authenticated()는 유지 → 비로그인은 401, 로그인 USER는 @PreAuthorize가 403.
            .anyRequest().authenticated())
        // 401/403을 우리 ErrorResponse JSON으로 응답
        .exceptionHandling(e -> e
            .authenticationEntryPoint(authenticationEntryPoint)
            .accessDeniedHandler(accessDeniedHandler))
        // 우리 JWT 필터를 표준 인증 필터 앞에 끼움 — 컨트롤러 전에 SecurityContext를 채운다
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
