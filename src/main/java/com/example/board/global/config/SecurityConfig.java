package com.example.board.global.config;

import com.example.board.auth.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// 강의 단계 2 — JWT stateless 인증.
// JwtAuthenticationFilter가 Bearer 토큰에서 userId를 꺼내 request attribute에 심고,
// @LoginUserId Resolver가 그 값을 읽어 인가를 판단한다. 서버는 세션을 만들지 않는다(STATELESS).
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthenticationFilter;

  // BCrypt: 단방향 해시 + salt 자동 포함. 비밀번호 저장의 표준.
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
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
        // 모든 요청 허용 — 인증/인가는 @LoginUserId Resolver와 서비스가 직접 판단
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        // 우리 JWT 필터를 표준 인증 필터 앞에 끼움 — 컨트롤러 전에 토큰→userId 준비
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
