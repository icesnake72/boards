---
step: 3
track: auth
tags: [auth, security]
requires: ["[[JWT-AUTH]]", "[[SECURITY-FILTER-CHAIN]]"]
status: 완료
---

# Spring Security 표준 인증/인가 — 클래스별 역할과 전환 (단계 3)

**과정명**: 강의용 Spring Boot 게시판 — 단계 3 (수동 JWT → Spring Security 표준)
**대상**: 단계 2(JWT, 수동 `@LoginUserId`)를 마친 수강생
**브랜치**: `step3-spring-security` (단계 2는 `step2-jwt`에 보존)
**관련 코드**: `auth/CustomUserDetails*.java`, `auth/AuthService.java`, `auth/jwt/JwtAuthenticationFilter.java`, `global/config/SecurityConfig.java`
**선수 지식**: [JWT-AUTH.md](JWT-AUTH.md), [SECURITY-FILTER-CHAIN.md](SECURITY-FILTER-CHAIN.md)

---

## 학습 목표

이 문서를 끝내면 수강생은:

- `UserDetails` / `UserDetailsService`가 무엇이고 왜 필요한지 설명할 수 있다
- `AuthenticationManager`에 인증을 위임하는 표준 흐름을 그릴 수 있다
- 수동 방식(`@LoginUserId`)이 표준(`@AuthenticationPrincipal`)으로 어떻게 바뀌는지 안다
- 선언적 인가(`hasRole`)와 자원 소유권 인가(서비스 검사)의 차이를 구분한다
- 401/403을 커스텀 JSON으로 응답하는 `EntryPoint`/`AccessDeniedHandler`를 안다

---

## 1. 왜 표준으로 가는가

단계 1·2는 인증/인가를 **직접 손으로** 구현했다. 메커니즘을 투명하게 보여주기 위함이었다. 이제 그 위에서 **Spring Security 표준**으로 갈아끼운다. 표준을 쓰면 다음을 공짜로 얻는다:

- `@AuthenticationPrincipal`로 로그인 사용자 주입
- `hasRole(...)` / `@PreAuthorize`로 선언적 권한 검사
- 계정 잠금/만료 등 상태 처리, 표준 인증 흐름
- 다른 Spring Security 기능(OAuth2, 메서드 보안 등)과의 자연스러운 연결

> **핵심**: 단계 2에서 직접 만든 `@LoginUserId` + ArgumentResolver는 사실 Spring Security의 `@AuthenticationPrincipal`을 손으로 재현한 것이었다. 단계 3은 "직접 만든 것을 표준으로 대체"한다.

---

## 2. 표준 인증의 핵심 부품

```
[로그인]                                  [매 요청]
AuthenticationManager                     JwtAuthenticationFilter
   │                                          │ 토큰 검증
   ▼                                          ▼ username 추출
DaoAuthenticationProvider                  CustomUserDetailsService
   │  (자동 구성)                              │ loadUserByUsername
   ▼                                          ▼
CustomUserDetailsService.loadUserByUsername   CustomUserDetails
   │                                          │
   ▼                                          ▼
CustomUserDetails (UserDetails)            SecurityContext에 Authentication 저장
   │ + PasswordEncoder.matches
   ▼
인증 성공 → JWT 발급
```

두 경로(로그인 / 매 요청) **모두** `CustomUserDetailsService`로 사용자를 로딩한다는 점이 표준의 핵심이다.

---

## 3. 클래스별 역할 + 실제 코드

### 3-1. `CustomUserDetails` — User를 Security 표준으로 감싸는 어댑터

**역할**: 우리 `User` 엔티티를 Spring Security가 이해하는 `UserDetails` 인터페이스로 변환한다.

```java
public class CustomUserDetails implements UserDetails {

  private final User user;

  public Long getId() {            // 컨트롤러에서 userId가 필요할 때
    return user.getId();
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    // hasRole("ADMIN")은 내부적으로 "ROLE_ADMIN" 권한을 찾는다
    return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
  }

  @Override public String getPassword() { return user.getPassword(); }
  @Override public String getUsername() { return user.getUsername(); }
  // isAccountNonExpired 등 상태 플래그는 모두 true
}
```

> **`ROLE_` 접두어 규칙**: `hasRole("ADMIN")`은 실제로는 `ROLE_ADMIN` 권한을 확인한다. 그래서 authority 문자열을 `"ROLE_" + role.name()`으로 만든다. (`hasAuthority("ROLE_ADMIN")`과 동일)

### 3-2. `CustomUserDetailsService` — 표준 사용자 로딩 진입점

**역할**: username으로 사용자를 DB에서 불러와 `UserDetails`로 반환한다. Spring Security의 단 하나의 표준 인터페이스(`UserDetailsService`)를 구현한다.

```java
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

  private final UserRepository userRepository;

  @Override
  public UserDetails loadUserByUsername(String username) {
    return userRepository.findByUsername(username)
        .map(CustomUserDetails::new)
        .orElseThrow(() -> new UsernameNotFoundException(username));
  }
}
```

> **한 곳, 두 사용처**: 로그인 시 `AuthenticationManager`가, 매 요청 시 `JwtAuthenticationFilter`가 모두 이 메서드를 호출한다.

### 3-3. `AuthenticationManager` — 인증을 위임받는 표준 매니저

**역할**: 단계 2에서 `AuthService`가 직접 하던 "사용자 로딩 + 비밀번호 비교"를 Spring에 위임한다.

`SecurityConfig`:

```java
@Bean
public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
    throws Exception {
  return configuration.getAuthenticationManager();
}
```

> 빈으로 노출만 하면 Spring이 `CustomUserDetailsService` + `PasswordEncoder`로 **`DaoAuthenticationProvider`를 자동 구성**한다. 우리가 Provider를 직접 만들 필요가 없다.

### 3-4. 로그인 — `AuthService.login` (표준 위임)

```java
@Transactional(readOnly = true)
public TokenResponse login(LoginRequest request) {
  try {
    // ① AuthenticationManager에 인증 위임 (내부에서 loadUserByUsername + matches)
    Authentication authentication = authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(request.username(), request.password()));
    // ② 인증된 principal에서 username을 꺼내 토큰 발급
    CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();
    String accessToken = tokenProvider.createToken(principal.getUsername());
    return TokenResponse.bearer(accessToken, accessTokenValiditySeconds);
  } catch (AuthenticationException e) {
    // ③ 실패(없는 사용자/비번 불일치)는 같은 401로 (user enumeration 방지)
    throw new UnauthorizedException(ErrorCode.LOGIN_FAILED);
  }
}
```

| 단계 2 (수동) | 단계 3 (표준) |
|--------------|--------------|
| `findByUsername` + `passwordEncoder.matches` 직접 | `authenticationManager.authenticate(...)` 위임 |
| 실패 시 직접 `throw` | `AuthenticationException` catch → 변환 |

### 3-5. `JwtAuthenticationFilter` — SecurityContext에 인증 정보 설정

**역할**: 단계 2는 userId를 request attribute에 심었지만, 단계 3은 **표준 저장소인 `SecurityContext`** 에 `Authentication`을 설정한다.

```java
String token = resolveToken(request);
if (token != null && tokenProvider.validateToken(token)) {
  String username = tokenProvider.getUsername(token);
  UserDetails userDetails = userDetailsService.loadUserByUsername(username);
  UsernamePasswordAuthenticationToken authToken =
      new UsernamePasswordAuthenticationToken(
          userDetails, null, userDetails.getAuthorities());
  authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
  SecurityContextHolder.getContext().setAuthentication(authToken);   // ← 표준 저장소
}
filterChain.doFilter(request, response);
```

| 단계 2 | 단계 3 |
|--------|--------|
| 토큰 subject = userId | 토큰 subject = **username** (loadUserByUsername 재사용 위해) |
| `request.setAttribute(LOGIN_USER_ID, userId)` | `SecurityContextHolder.getContext().setAuthentication(...)` |
| 권한 정보 없음 | `userDetails.getAuthorities()` (ROLE_*) 포함 → 인가에 사용 |

### 3-6. 컨트롤러 — `@AuthenticationPrincipal` 표준 주입

```java
@PostMapping("/boards/{boardId}/posts")
@ResponseStatus(HttpStatus.CREATED)
public PostResponse create(
    @PathVariable Long boardId,
    @AuthenticationPrincipal CustomUserDetails userDetails,   // ← SecurityContext의 principal
    @Valid @RequestBody PostCreateRequest request) {
  return postService.create(boardId, userDetails.getId(), request);
}
```

| 단계 2 | 단계 3 |
|--------|--------|
| `@LoginUserId Long userId` (커스텀) | `@AuthenticationPrincipal CustomUserDetails userDetails` (표준) |
| 우리가 만든 Resolver가 주입 | Spring 내장 Resolver가 주입 |

> `@AuthenticationPrincipal`은 `SecurityContext`의 `Authentication.getPrincipal()`(=우리 `CustomUserDetails`)을 꺼내 주입한다. `@LoginUserId`가 하던 일을 Spring 표준이 대신한다.

### 3-7. 선언적 인가 — `SecurityConfig.authorizeHttpRequests`

**역할**: 단계 2에서 `BoardService.validateAdmin`이 수동으로 하던 권한 검사를, URL 규칙으로 선언한다.

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/auth/**").permitAll()
    .requestMatchers(HttpMethod.GET, "/api/v1/boards/**").permitAll()
    .requestMatchers(HttpMethod.GET, "/api/v1/posts/**").permitAll()
    .requestMatchers("/api/v1/profiles/me").authenticated()        // /me 먼저 매칭
    .requestMatchers(HttpMethod.GET, "/api/v1/profiles/*").permitAll()
    .requestMatchers(HttpMethod.POST, "/api/v1/boards").hasRole(Role.ADMIN.name())
    .requestMatchers(HttpMethod.PUT, "/api/v1/boards/*").hasRole(Role.ADMIN.name())
    .requestMatchers(HttpMethod.DELETE, "/api/v1/boards/*").hasRole(Role.ADMIN.name())
    .anyRequest().authenticated())
```

> `hasRole(...)`은 String만 받지만, 리터럴 `"ADMIN"` 대신 `Role.ADMIN.name()`을 쓰면 enum이 단일 출처가 되어 리팩토링에 안전하다. (`hasRole`이 내부적으로 `ROLE_` 접두어를 붙이므로 `"ADMIN"`을 넘기면 `ROLE_ADMIN`을 검사 — `CustomUserDetails`의 authority와 짝이 맞는다.)

> **매칭 순서 주의 2가지**:
> - `/profiles/me`(인증 필요)를 `/profiles/*`(공개)보다 **먼저** 선언해야 한다. 위에서부터 먼저 맞는 규칙이 적용되기 때문.
> - `/boards/*`는 세그먼트 하나만 매칭하므로 `/boards/{boardId}/posts`(게시글)는 ADMIN 규칙에 안 걸린다.

### 3-8. 401/403 응답 — EntryPoint / AccessDeniedHandler

선언적 인가가 막으면 Spring Security가 기본 응답(빈 본문/HTML)을 내므로, **우리 `ErrorResponse` JSON으로 바꾼다.**

```java
// RestAuthenticationEntryPoint — 미인증(401)
public void commence(req, res, authException) {
  writeError(res, ErrorCode.LOGIN_REQUIRED);   // 401 JSON
}

// RestAccessDeniedHandler — 권한 부족(403)
//   ErrorCode.ACCESS_DENIED 로 403 JSON
```

`SecurityConfig`에서 연결:

```java
.exceptionHandling(e -> e
    .authenticationEntryPoint(authenticationEntryPoint)   // 401
    .accessDeniedHandler(accessDeniedHandler))            // 403
```

---

## 4. 두 종류의 403 — 중요한 구분

단계 3에는 403이 **두 곳**에서 나온다. 이 차이가 핵심 강의 포인트다.

| 종류 | 어디서 | 예 | code |
|------|--------|-----|------|
| **role 기반 인가** | SecurityConfig (선언적) | USER가 게시판 생성 | `ACCESS_DENIED` |
| **자원 소유권 인가** | PostService (코드) | 남의 글 수정/삭제 | `POST_ACCESS_DENIED` |

```java
// PostService — 소유권은 선언적으로 못 막는다 (DB의 작성자와 비교해야 함)
private void validateAuthor(Post post, Long userId) {
  if (!post.isAuthor(userId)) {
    throw new ForbiddenException(ErrorCode.POST_ACCESS_DENIED);
  }
}
```

> **왜 소유권은 서비스에 남나?** `hasRole("ADMIN")`처럼 "역할"은 요청만 보고 판단되지만, "이 글의 작성자인가?"는 **DB의 데이터(글의 user_id)** 를 봐야 안다. URL 규칙만으로는 불가능하므로 서비스 계층에 남긴다.

---

## 5. 전환 코딩 순서 (단계 2 → 3)

원칙: **표준 부품을 먼저 만들고(A~C) → 호출부를 갈아끼운 뒤 옛 장치를 제거(D)**. 표준 부품을 다 만들어 둔 다음 마지막에 옛 코드를 걷어내야 컴파일 충돌이 가장 적다.

### Phase A — 표준 부품 만들기 (독립적, 먼저)

1. **`CustomUserDetails`** — `User` → `UserDetails` 어댑터. 의존성 없음. 다른 표준 코드가 모두 사용하므로 가장 먼저. (`getId()`, `getAuthorities()`)
2. **`CustomUserDetailsService`** — `loadUserByUsername`. 1번 + `UserRepository` 필요. 로그인·매 요청 인증이 **모두** 사용하는 토대.

### Phase B — 발급/검증 경로를 표준으로

3. **`JwtTokenProvider`: subject userId → username.** `createToken(String username)`, `getUsername(token)`. 로그인(5)과 필터(6)가 의존하므로 먼저.
4. **`AuthenticationManager` 빈 노출** (SecurityConfig). 2번 + `PasswordEncoder`로 `DaoAuthenticationProvider` 자동 구성 → 2번 다음.
5. **`AuthService.login` 전환** — `authenticationManager.authenticate()` 위임. 4 + 3 필요. 실패는 `AuthenticationException` → `LOGIN_FAILED`.
6. **`JwtAuthenticationFilter` 전환** — request attribute 제거, `SecurityContextHolder...setAuthentication(...)`. 2(loadUserByUsername) + 3(getUsername) 필요.

### Phase C — 인가/예외 표준화

7. **`ErrorCode.ACCESS_DENIED`(403) 추가 + `RestAuthenticationEntryPoint`(401) / `RestAccessDeniedHandler`(403).** 8번이 연결하므로 먼저.
8. **`SecurityConfig` 완성** — `authorizeHttpRequests`(공개/`hasRole`/authenticated) + `exceptionHandling(entryPoint, accessDeniedHandler)` + 필터 등록. 6 + 7 필요.

### Phase D — 호출부 전환 & 옛 코드 제거 (컴파일 함께 묶임)

9. **컨트롤러 3곳: `@LoginUserId Long` → `@AuthenticationPrincipal CustomUserDetails`** (`userDetails.getId()`). **BoardService**: 인가가 8번 `hasRole`로 이동 → `validateAdmin`·userId 파라미터 제거. **PostService**: 작성자 소유권 검사는 **유지**.
10. **옛 장치 제거**: `LoginUserId`, `LoginUserIdArgumentResolver`, `AuthConst` 삭제 + `WebConfig`의 Resolver 등록 제거(Page 직렬화는 유지). 9번에서 사용처를 모두 바꾼 **뒤** 제거해야 컴파일이 안 깨진다.

### Phase E — 검증

11. 테스트 갱신(삭제: `LoginUserIdArgumentResolverTest` / 신규: `CustomUserDetailsServiceTest`, `SecurityIntegrationTest`) + `./gradlew build` + curl 런타임 확인.

```
A. 표준 부품   1.CustomUserDetails → 2.CustomUserDetailsService
B. 경로 표준화 3.JwtTokenProvider(username) → 4.AuthenticationManager빈
               → 5.AuthService.login(위임) → 6.JwtAuthenticationFilter(SecurityContext)
C. 인가/예외   7.ACCESS_DENIED + EntryPoint/AccessDeniedHandler → 8.SecurityConfig
D. 호출부/정리 9.컨트롤러 @AuthenticationPrincipal + BoardService → 10.옛 장치 제거
E. 검증        11.테스트 + 빌드/런타임
```

> **핵심 주의 — 컴파일 결합**: 9·10번(컨트롤러 교체 ↔ `@LoginUserId` 제거)은 서로 맞물려 있어, 사용처를 모두 바꾼 뒤(9) 제거(10)해야 한다. 프로젝트 전체가 다시 컴파일되는 시점은 **D 단계 완료 후**다 — 단계 1·2의 점진적 전환과 달리 빅뱅에 가깝다.

> **통찰**: 단계 2→3은 "직접 만든 것을 표준으로 1:1 대체"라서 **만들기(A~C) → 갈아끼우기(D)** 두 국면으로 나뉜다.

---

## 6. 단계 2 → 3 전환 요약

| 항목 | 단계 2 (수동 JWT) | 단계 3 (Security 표준) |
|------|-------------------|------------------------|
| 사용자 주입 | `@LoginUserId` + 커스텀 Resolver | `@AuthenticationPrincipal CustomUserDetails` |
| 인증(login) | 직접 `findByUsername`+`matches` | `AuthenticationManager.authenticate()` |
| 사용자 로딩 | 없음(직접 조회) | `CustomUserDetailsService.loadUserByUsername` |
| 필터의 저장 위치 | request attribute | `SecurityContext` |
| 토큰 subject | userId | username |
| role 인가 | 서비스 `validateAdmin` | `hasRole("ADMIN")` 선언적 |
| 소유권 인가 | 서비스 `isAuthor` | **유지** (서비스) |
| 401/403 | 도메인 예외 | EntryPoint/AccessDeniedHandler |
| **제거된 것** | — | `@LoginUserId`, `LoginUserIdArgumentResolver`, `AuthConst` |

---

## 7. 핵심 요약 한 장

```
┌────────────────────────────────────────────────────────────────────┐
│ 표준 부품                                                            │
│   CustomUserDetails        User → UserDetails 어댑터 (authorities)   │
│   CustomUserDetailsService loadUserByUsername (로그인·매요청 공용)   │
│   AuthenticationManager    인증 위임 (DaoAuthenticationProvider 자동) │
│                                                                     │
│ 로그인:  authenticate() → 성공 → username으로 JWT 발급              │
│ 매 요청: JWT 검증 → loadUserByUsername → SecurityContext에 저장      │
│ 주입:    @AuthenticationPrincipal CustomUserDetails                  │
│                                                                     │
│ 인가:                                                               │
│   role     → SecurityConfig hasRole("ADMIN")  → 403 ACCESS_DENIED   │
│   소유권   → PostService isAuthor             → 403 POST_ACCESS_DENIED│
│   미인증   → AuthenticationEntryPoint         → 401 LOGIN_REQUIRED   │
│                                                                     │
│ 제거: @LoginUserId / ArgumentResolver / AuthConst (표준이 대체)      │
└────────────────────────────────────────────────────────────────────┘
```

---

## 부록. 자주 나오는 질문 (FAQ)

| 질문 | 답변 요약 |
|------|-----------|
| `UserDetailsService`는 왜 필요한가? | Spring Security가 "username으로 사용자를 어떻게 불러올지"를 위임하는 표준 지점. 로그인과 매 요청 인증이 모두 사용. |
| `hasRole("ADMIN")`인데 왜 authority는 `ROLE_ADMIN`? | `hasRole`이 자동으로 `ROLE_` 접두어를 붙여 확인하기 때문. authority는 `ROLE_ADMIN`으로 만든다. |
| 토큰 subject를 왜 userId→username으로 바꿨나? | 표준 `loadUserByUsername(username)`을 그대로 재사용하려고. username이 있으면 매 요청 사용자 로딩이 자연스럽다. |
| `@LoginUserId`를 굳이 없앤 이유는? | `@AuthenticationPrincipal`이 정확히 같은 일을 표준으로 하기 때문. 중복 제거. |
| 매 요청 DB 조회(loadUserByUsername)는 부담 아닌가? | stateless라 매 요청 로딩이 표준. 부담되면 캐시나 토큰에 권한을 담는 방식으로 최적화(후속 주제). |
| 소유권 검사는 왜 선언적으로 못 하나? | role은 요청만으로 판단되지만, 작성자 여부는 DB 데이터를 봐야 알 수 있어 서비스에 남긴다. |
| 403이 왜 두 종류인가? | role 거부(SecurityConfig, `ACCESS_DENIED`)와 소유권 거부(서비스, `POST_ACCESS_DENIED`)는 발생 위치·의미가 다르다. |
