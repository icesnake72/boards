# Spring Security Filter Chain — 구성과 실행 순서

**과정명**: 강의용 Spring Boot 게시판 — 단계 2 (JWT)
**대상**: JWT 인증을 구현한 수강생
**관련 코드**: `global/config/SecurityConfig.java`, `auth/jwt/JwtAuthenticationFilter.java`
**선수 지식**: [JWT-AUTH.md](JWT-AUTH.md), 서블릿 필터 개념

---

## 학습 목표

이 문서를 끝내면 수강생은:

- 요청 하나가 컨트롤러에 닿기까지 **어떤 필터들을 거치는지** 그릴 수 있다
- 우리가 만든 `JwtAuthenticationFilter`가 **체인의 어디에** 끼는지 안다
- `SecurityConfig`의 각 설정이 필터 체인을 **어떻게 바꾸는지** 설명할 수 있다
- 필터 / DispatcherServlet / ArgumentResolver / Controller의 **실행 순서**를 구분한다

---

## 1. 큰 그림 — 요청이 거치는 단계

HTTP 요청 하나는 컨트롤러에 닿기 전에 **여러 겹의 필터**를 통과한다.

```
HTTP 요청
   │
   ▼
[ Servlet Filter Chain ]                  ← 서블릿 컨테이너(Tomcat) 레벨
   │   ...기본 필터들...
   │   FilterChainProxy (Spring Security의 진입점, 단 하나의 서블릿 필터)
   │        │
   │        ▼
   │   [ Security Filter Chain ]          ← Spring Security 내부의 필터 묶음
   │        ├ (1) DisableEncodeUrlFilter
   │        ├ (2) SecurityContextHolderFilter
   │        ├ (3) HeaderWriterFilter
   │        ├ (4) ★ JwtAuthenticationFilter   ← 우리가 끼운 필터
   │        ├ (5) (csrf/formLogin/httpBasic — 우리는 disable)
   │        ├ (6) AuthorizationFilter          ← permitAll 판단
   │        └ ...
   │
   ▼
[ DispatcherServlet ]                     ← Spring MVC 진입
   │   HandlerMapping → 어떤 컨트롤러 메서드인지 결정
   │
   ▼
[ ArgumentResolver ]  LoginUserIdArgumentResolver
   │   request attribute에서 userId를 꺼내 @LoginUserId에 주입
   │
   ▼
[ Controller ]  예: PostController.create(@LoginUserId Long userId, ...)
   │
   ▼
HTTP 응답
```

> **핵심**: Spring Security 전체는 사실 **하나의 서블릿 필터(`FilterChainProxy`)** 로 등록되고, 그 안에서 다시 **여러 보안 필터**가 순서대로 실행된다. 우리가 만든 JWT 필터는 그 "안쪽 묶음"의 한 자리를 차지한다.

---

## 2. 우리 프로젝트의 SecurityConfig

`global/config/SecurityConfig.java`:

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
  http
      .csrf(AbstractHttpConfigurer::disable)
      .formLogin(AbstractHttpConfigurer::disable)
      .httpBasic(AbstractHttpConfigurer::disable)
      .sessionManagement(session ->
          session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
      .addFilterBefore(jwtAuthenticationFilter,
          UsernamePasswordAuthenticationFilter.class);
  return http.build();
}
```

### 각 설정이 필터 체인에 미치는 영향

| 설정 | 필터 체인에 미치는 영향 |
|------|------------------------|
| `csrf().disable()` | CSRF 검증 필터(`CsrfFilter`)를 **체인에서 제거** |
| `formLogin().disable()` | 폼 로그인 필터(`UsernamePasswordAuthenticationFilter`) **제거** |
| `httpBasic().disable()` | HTTP Basic 인증 필터(`BasicAuthenticationFilter`) **제거** |
| `sessionManagement(STATELESS)` | 세션을 만들지 않음 — `SecurityContext`를 세션에 저장하지 않음 |
| `authorizeHttpRequests(permitAll)` | `AuthorizationFilter`가 **모든 요청을 통과**시킴 |
| `addFilterBefore(jwtFilter, ...)` | **우리 JWT 필터를 체인에 추가** (지정 필터 앞에) |

> **왜 다 disable하고 permitAll인가?**
> 단계 1의 철학을 이어, 인증/인가를 Spring Security 표준 필터가 아니라 **우리 코드(JWT 필터 + `@LoginUserId` Resolver)** 가 직접 담당하기 때문이다. Security는 "필터를 끼울 자리"와 BCrypt만 빌려 쓴다.

---

## 3. `addFilterBefore` — 우리 필터는 어디에 끼나

```java
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
```

"`UsernamePasswordAuthenticationFilter` **앞에** `jwtAuthenticationFilter`를 넣어라"는 뜻이다.

```
... → JwtAuthenticationFilter → (UsernamePasswordAuthenticationFilter 자리) → ... → AuthorizationFilter → ...
        ↑ 우리 필터가 여기                ↑ formLogin disable이라 실제론 비어 있음
```

| 메서드 | 의미 |
|--------|------|
| `addFilterBefore(A, B.class)` | B 앞에 A를 넣는다 |
| `addFilterAfter(A, B.class)` | B 뒤에 A를 넣는다 |
| `addFilterAt(A, B.class)` | B와 같은 위치에 A를 둔다 |

> `UsernamePasswordAuthenticationFilter`는 formLogin을 disable해서 실제로는 동작하지 않지만, **위치 기준점**으로는 여전히 유효하다. "인증을 시도하는 표준 필터 자리쯤"에 우리 JWT 필터를 두는 관용적 패턴이다. 그래서 JWT 필터는 인가 판단(`AuthorizationFilter`)보다 **앞**에서 실행되어, 인가 전에 userId를 준비해 둔다.

---

## 4. 실행 순서 — 보호 API 요청을 따라가기

요청: `POST /api/v1/boards/1/posts` + `Authorization: Bearer eyJ...`

```
① SecurityContextHolderFilter      보안 컨텍스트 준비 (STATELESS라 비어 있음)
② HeaderWriterFilter               보안 응답 헤더 부착(X-Frame-Options 등)
③ ★ JwtAuthenticationFilter         doFilterInternal() 호출
        ├ resolveToken(request)     "Bearer " 떼고 토큰 추출
        ├ validateToken(token)      서명·만료 검증
        └ request.setAttribute(LOGIN_USER_ID, userId)   ← userId 심기
④ AuthorizationFilter              permitAll → 통과 (인가는 Resolver가 담당)
   ───────── 여기까지 Security Filter Chain ─────────
⑤ DispatcherServlet                어떤 컨트롤러 메서드인지 결정
⑥ LoginUserIdArgumentResolver      request에서 userId 꺼내 @LoginUserId 주입
        └ userId 없으면 401 (LOGIN_REQUIRED)
⑦ PostController.create(...)       실제 비즈니스 로직 실행
```

> **③ → ⑥의 연결이 핵심**: 필터(③)가 `request`에 심은 userId를, 한참 뒤 ArgumentResolver(⑥)가 꺼낸다. 둘 사이를 잇는 매개체가 **request attribute**(`AuthConst.LOGIN_USER_ID`)다.

### 비로그인 요청이면?

```
③ JwtAuthenticationFilter   토큰 없음 → attribute 안 심고 통과
④ AuthorizationFilter       permitAll → 통과
⑥ ArgumentResolver          getAttribute → null → 401 LOGIN_REQUIRED
```

→ 필터는 막지 않고, **최종 거절은 ArgumentResolver가** 한다. 공개 엔드포인트는 `@LoginUserId`가 없어 ⑥을 거치지 않으므로 그대로 통과한다.

---

## 5. 필터 vs Resolver vs Controller — 역할 구분

| 구성요소 | 레벨 | 언제 | 역할 |
|----------|------|------|------|
| `JwtAuthenticationFilter` | 서블릿 필터 | 컨트롤러 전, 요청당 1회 | 토큰 검증 → userId 심기 |
| `AuthorizationFilter` | 서블릿 필터 | 필터 후반 | 접근 허용/차단 (우리는 permitAll) |
| `LoginUserIdArgumentResolver` | Spring MVC | 컨트롤러 직전 | request의 userId를 파라미터로 주입 |
| `Controller` | Spring MVC | 마지막 | 비즈니스 로직 |

> **헷갈리지 말 것**: 필터는 `SecurityConfig`에서 등록하고, ArgumentResolver는 `WebConfig`에서 등록한다. 둘은 서로 다른 확장 지점이며 실행 시점도 다르다(필터가 먼저, Resolver가 나중).

---

## 6. 단계 1(세션)과 비교하면

| | 단계 1 (세션) | 단계 2 (JWT) |
|--|--------------|--------------|
| SecurityFilterChain | permitAll, 커스텀 필터 없음 | permitAll + **JwtAuthenticationFilter 추가** |
| userId를 request에 심는 주체 | Tomcat이 JSESSIONID로 세션 조회 | 우리 JWT 필터가 토큰 파싱 |
| 세션 정책 | 기본(세션 생성) | **STATELESS** (세션 안 만듦) |
| Resolver가 읽는 곳 | session attribute | request attribute |

> 단계 1에서 "세션을 찾아 userId를 준비"하던 일을, 단계 2에서는 우리가 만든 **필터가 토큰을 검증해 대신**한다. 필터 체인에 한 칸을 추가한 것이 전환의 핵심이다.

---

## 7. 핵심 요약 한 장

```
┌────────────────────────────────────────────────────────────────────┐
│ 요청 → [Security Filter Chain] → DispatcherServlet → Resolver → 컨트롤러 │
│                                                                     │
│ Security Filter Chain (우리 설정 기준):                              │
│   SecurityContextHolderFilter                                       │
│   HeaderWriterFilter                                                │
│   ★ JwtAuthenticationFilter   ← addFilterBefore(...)               │
│        resolveToken → validateToken → setAttribute(userId)         │
│   AuthorizationFilter          ← permitAll (전부 통과)             │
│                                                                     │
│ disable한 필터: CsrfFilter / Form / HttpBasic                       │
│ 세션 정책: STATELESS                                                │
│                                                                     │
│ 최종 인증 판단: 컨트롤러 직전 LoginUserIdArgumentResolver (null→401) │
└────────────────────────────────────────────────────────────────────┘
```

---

## 부록. 실제 필터 순서를 직접 확인하는 법

애플리케이션을 띄울 때 로깅 레벨을 올리면 Spring이 구성한 필터 목록과 순서가 콘솔에 출력된다.

```yaml
# application.yaml (확인용, 강의 시연 후 제거)
logging:
  level:
    org.springframework.security.web.FilterChainProxy: DEBUG
```

또는 기동 로그에서 다음 형태의 줄을 찾는다:

```
Will secure any request with [
  org.springframework.security.web.context.SecurityContextHolderFilter,
  org.springframework.security.web.header.HeaderWriterFilter,
  com.example.board.auth.jwt.JwtAuthenticationFilter,     ← 우리 필터
  org.springframework.security.web.access.intercept.AuthorizationFilter
]
```

> 출력되는 목록에 `CsrfFilter`, `UsernamePasswordAuthenticationFilter`, `BasicAuthenticationFilter`가 **없는 것**을 확인하라 — 우리가 disable했기 때문이다.

---

## 부록. 자주 나오는 질문 (FAQ)

| 질문 | 답변 요약 |
|------|-----------|
| Security 전체가 필터 하나라고? | 네. `FilterChainProxy`라는 단일 서블릿 필터가 내부에서 보안 필터들을 순서대로 실행합니다. |
| 왜 `UsernamePasswordAuthenticationFilter` 앞에 끼나? | 인증 시도 필터 자리쯤에 둬서 인가(AuthorizationFilter)보다 먼저 userId를 준비하려고. formLogin은 disable이라 위치 기준점으로만 씁니다. |
| 필터가 401을 안 내고 Resolver가 내는 이유는? | 공개 엔드포인트가 있어 필터는 막지 않고 통과시킴. 로그인 필수 판단은 `@LoginUserId`가 붙은 곳에서만 합니다. |
| 필터와 ArgumentResolver 등록 위치는? | 필터는 `SecurityConfig`(addFilterBefore), Resolver는 `WebConfig`(addArgumentResolvers). |
| STATELESS면 SecurityContext는? | 매 요청 비어 있고 세션에 저장하지 않습니다. 우리는 SecurityContext 대신 request attribute로 userId를 전달합니다. |
| 필터 순서를 눈으로 보려면? | `FilterChainProxy`를 DEBUG로 켜거나 기동 로그의 "Will secure any request with [...]"를 확인. |
