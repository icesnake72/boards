package com.example.board.global.config;

import com.example.board.auth.jwt.JwtAuthenticationFilter;
import com.example.board.auth.oauth2.CookieOAuth2AuthorizationRequestRepository;
import com.example.board.auth.oauth2.CustomOAuth2UserService;
import com.example.board.auth.oauth2.CustomOidcUserService;
import com.example.board.auth.oauth2.OAuth2LoginFailureHandler;
import com.example.board.auth.oauth2.OAuth2LoginSuccessHandler;
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

  // 단계 8 주의 — oauth2 부품들은 생성자 필드가 아니라 "빈 메서드 파라미터"로 받는다.
  // CustomOAuth2UserService가 PasswordEncoder(이 클래스의 @Bean)를 쓰므로, 생성자로 받으면
  // SecurityConfig → CustomOAuth2UserService → PasswordEncoder → SecurityConfig 순환이 생긴다.
  // 메서드 파라미터는 SecurityConfig 인스턴스가 만들어진 뒤 해석되므로 고리가 끊긴다.
  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      CookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository,
      CustomOAuth2UserService customOAuth2UserService,
      CustomOidcUserService customOidcUserService,
      OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
      OAuth2LoginFailureHandler oAuth2LoginFailureHandler) throws Exception {
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
            // 단계 7: 카카오 OAuth2 — 로그인 전 단계이므로 당연히 공개. 콜백은 카카오發 리다이렉트로 들어온다.
            .requestMatchers("/api/oauth/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/boards/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/posts/**").permitAll()
            // /me는 인증 필요 — 타인 프로필(/profiles/{userId})보다 먼저 매칭해야 한다
            .requestMatchers("/api/v1/profiles/me").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/v1/profiles/*").permitAll()
            // 단계 6: board 생성/수정/삭제의 ADMIN 인가는 BoardController의 @PreAuthorize("hasRole('ADMIN')")로 이동.
            // 공개 GET 규칙과 anyRequest().authenticated()는 유지 → 비로그인은 401, 로그인 USER는 @PreAuthorize가 403.
            .anyRequest().authenticated())
        // 단계 8: OAuth 표준화 — 단계 7 수동 구현(KakaoOAuthController/Client)이 하던 전 과정을
        // oauth2Login 필터들이 대신한다. 시작 URL은 /oauth2/authorization/kakao (표준 기본값),
        // 콜백은 /login/oauth2/code/kakao — 카카오 콘솔에 이 콜백 URI 등록 필수.
        .oauth2Login(oauth -> oauth
            // state 저장을 세션(기본값) 대신 쿠키로 — STATELESS 유지
            .authorizationEndpoint(endpoint ->
                endpoint.authorizationRequestRepository(cookieAuthorizationRequestRepository))
            // 사용자 조회 직후 find-or-create (단계 3 CustomUserDetailsService의 OAuth판)
            // .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
            //   ↑ 단계 9 처리에 의해 제거 — 아래처럼 oidcUserService 연결을 추가하며 확장했다
            // 단계 9: 두 경로를 각각 연결 — 카카오(순수 OAuth2)는 userService,
            // 구글(openid scope → OIDC)은 oidcUserService가 담당한다. 정책은 같은 upsert로 수렴.
            .userInfoEndpoint(userInfo -> userInfo
                .userService(customOAuth2UserService)
                .oidcUserService(customOidcUserService))
            // 성공: 우리 JWT + refresh 쿠키 / 실패: 401 JSON (기본값은 화면 리다이렉트라 교체)
            .successHandler(oAuth2LoginSuccessHandler)
            .failureHandler(oAuth2LoginFailureHandler))
        // 401/403을 우리 ErrorResponse JSON으로 응답
        .exceptionHandling(e -> e
            .authenticationEntryPoint(authenticationEntryPoint)
            .accessDeniedHandler(accessDeniedHandler))
        // 우리 JWT 필터를 표준 인증 필터 앞에 끼움 — 컨트롤러 전에 SecurityContext를 채운다
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
