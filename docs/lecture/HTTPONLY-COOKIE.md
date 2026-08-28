---
step: 5
track: auth
tags: [auth, cookie, security]
requires: ["[[REFRESH-TOKEN]]", "[[HTTP-SESSION]]"]
status: 완료
---

# httpOnly 쿠키 — Refresh Token 안전한 전달 (단계 5)

**과정명**: 강의용 Spring Boot 게시판 — 단계 5 (refresh token을 쿠키로)
**대상**: 단계 4(refresh token, 본문 전달)를 마친 수강생
**브랜치/태그**: `step5-httponly-cookie` / `v-step5-cookie`
**관련 코드**: `auth/RefreshCookieFactory.java`, `auth/AuthController.java`, `auth/dto/TokenPair.java`
**선수 지식**: [REFRESH-TOKEN.md](REFRESH-TOKEN.md), [HTTP-SESSION.md](HTTP-SESSION.md)(쿠키 개념)

---

## 학습 목표

이 문서를 끝내면 수강생은:

- 단계 4의 "본문 전달"이 가진 보안 약점(XSS)을 설명할 수 있다
- httpOnly / Secure / SameSite / Path 쿠키 속성의 의미를 안다
- access는 본문, refresh는 쿠키로 두는 **비대칭 설계**의 이유를 안다
- `@CookieValue` / `ResponseCookie`로 쿠키를 읽고 쓰는 법을 안다

---

## 1. 단계 4의 약점 — 본문 전달

단계 4는 refresh token을 **JSON 본문**으로 주고받았다:

```json
// 단계 4 로그인 응답
{ "accessToken": "...", "refreshToken": "9558855b-...", ... }
```

문제: 클라이언트가 이 refresh token을 **직접 저장**해야 한다. 흔히 `localStorage`에 넣는데, 그러면 **XSS 한 방에 14일짜리 고가치 토큰이 통째로 털린다**:

```js
// ❌ 주입된 악성 스크립트가 그대로 읽어감
localStorage.getItem('refreshToken')
```

> access token(1시간)이면 피해가 제한적이지만, refresh token(14일, access를 찍어냄)은 치명적이다. **가장 강하게 보호해야 할 토큰을 가장 취약하게 두는** 셈.

## 2. 해법 — httpOnly 쿠키

refresh token을 **httpOnly 쿠키**로 내려준다. 핵심은 `HttpOnly` 플래그:

```
Set-Cookie: refreshToken=...; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth
```

| 속성 | 의미 | 효과 |
|------|------|------|
| **HttpOnly** | JS가 `document.cookie`로 못 읽음 | **XSS 탈취 차단** (핵심) |
| **Secure** | HTTPS에서만 전송 | 도청 방지 |
| **SameSite=Strict** | 크로스 사이트 요청에 쿠키 미동봉 | CSRF 면 축소 |
| **Path=/api/v1/auth** | 이 경로 하위에만 쿠키 전송 | 노출 면적 최소화 (reissue/logout만) |

> JS가 못 읽으니 XSS가 발생해도 refresh token을 가져갈 수 없다. 그리고 브라우저가 **자동으로** 쿠키를 동봉하므로 클라이언트 코드가 토큰을 다룰 필요조차 없다.

## 3. 비대칭 설계 — access는 본문, refresh는 쿠키

| | access token | refresh token |
|--|-------------|---------------|
| 전달 | **응답 본문(JSON)** | **httpOnly 쿠키** |
| 클라이언트 보관 | 메모리(JS 변수) | (직접 보관 안 함 — 브라우저가 쿠키 관리) |
| 매 API 요청 | `Authorization: Bearer` 헤더에 직접 실음 | (안 보냄 — Path가 /auth라 API엔 미전송) |
| 재발급/로그아웃 | — | 브라우저가 자동 동봉 |

> **왜 access는 쿠키가 아니라 본문인가?** access token은 매 API 요청의 `Authorization` 헤더에 실어야 하는데, JS가 헤더를 만들려면 토큰을 읽을 수 있어야 한다. 그래서 본문→메모리. 짧은 수명이라 위험이 제한적. 반대로 refresh는 JS가 만질 일이 없으니 httpOnly 쿠키에 숨긴다.

## 4. 클래스 — RefreshCookieFactory

`auth/RefreshCookieFactory.java` — 쿠키를 만드는 헬퍼:

```java
@Component
public class RefreshCookieFactory {

  // 설정값 주입 (application.yaml의 app.refresh-cookie.*)
  public RefreshCookieFactory(
      @Value("${app.refresh-cookie.name}") String name,
      @Value("${app.refresh-cookie.secure}") boolean secure,
      @Value("${app.refresh-cookie.same-site}") String sameSite,
      @Value("${app.refresh-cookie.path}") String path) { ... }

  // 발급용
  public ResponseCookie create(String refreshToken, long maxAgeSeconds) {
    return ResponseCookie.from(name, refreshToken)
        .httpOnly(true).secure(secure).sameSite(sameSite)
        .path(path).maxAge(maxAgeSeconds).build();
  }

  // 삭제용 (로그아웃) — 같은 name/path에 빈 값 + maxAge=0
  public ResponseCookie expire() {
    return ResponseCookie.from(name, "")
        .httpOnly(true).secure(secure).sameSite(sameSite)
        .path(path).maxAge(0).build();
  }
}
```

`application.yaml`:

```yaml
app:
  refresh-cookie:
    name: refreshToken
    secure: ${APP_REFRESH_COOKIE_SECURE:false}   # 로컬 HTTP용 false. 운영은 반드시 true
    same-site: Strict
    path: /api/v1/auth
```

> **Secure=false 기본값 주의**: 로컬은 HTTP라 `Secure=true`면 브라우저/curl이 쿠키를 안 보내 테스트가 깨진다. 그래서 로컬 기본값은 false, **운영 배포 시 `APP_REFRESH_COOKIE_SECURE=true` 필수**.

## 5. 흐름 — 단계 4 대비 무엇이 바뀌었나

### 5-1. 로그인 — 본문엔 access만, refresh는 Set-Cookie

`AuthController.login`:

```java
@PostMapping("/login")
public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
  TokenPair tokens = authService.login(request);
  ResponseCookie refreshCookie =
      refreshCookieFactory.create(tokens.refreshToken(), tokens.refreshTokenValiditySeconds());
  return ResponseEntity.ok()
      .header(HttpHeaders.SET_COOKIE, refreshCookie.toString())   // refresh → 쿠키
      .body(TokenResponse.bearer(tokens.accessToken(), ...));     // access → 본문
}
```

응답:
```
HTTP/1.1 200 OK
Set-Cookie: refreshToken=...; HttpOnly; SameSite=Strict; Path=/api/v1/auth; Max-Age=1209600
{ "accessToken": "eyJ...", "tokenType": "Bearer", "expiresIn": 3600 }   ← refresh 없음!
```

### 5-2. 재발급 — 쿠키에서 읽음 (@CookieValue)

```java
@PostMapping("/reissue")
public ResponseEntity<TokenResponse> reissue(
    @CookieValue(name = "refreshToken", required = false) String refreshToken) {  // 본문 X, 쿠키 O
  if (refreshToken == null) {
    throw new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN);  // 쿠키 없으면 401
  }
  TokenPair tokens = authService.reissue(refreshToken);
  return ResponseEntity.ok().body(TokenResponse.bearer(tokens.accessToken(), ...));
}
```

> 클라이언트는 그냥 `POST /api/v1/auth/reissue`만 호출하면 된다 — **브라우저가 쿠키를 자동 동봉**하므로 토큰을 직접 실을 필요가 없다.

### 5-3. 로그아웃 — DB 삭제 + 쿠키 만료

```java
@PostMapping("/logout")
public ResponseEntity<Void> logout(
    @CookieValue(name = "refreshToken", required = false) String refreshToken) {
  if (refreshToken != null) {
    authService.logout(refreshToken);          // ① 서버 DB에서 삭제
  }
  ResponseCookie expired = refreshCookieFactory.expire();   // ② 클라이언트 쿠키도 만료(maxAge=0)
  return ResponseEntity.noContent()
      .header(HttpHeaders.SET_COOKIE, expired.toString()).build();
}
```

> 로그아웃은 **양쪽**을 지운다: 서버 DB의 refresh token(재발급 차단) + 클라이언트 쿠키(`Max-Age=0`으로 즉시 만료).

> [!NOTE]
> **후기 — 단계 15에서 확장**: 단계 15부터 logout은 쿠키의 refresh와 함께 **Authorization 헤더의 access token도** 받아 즉시 폐기한다(`logout(refreshToken, accessToken)` — jti를 Redis denylist에 잔여수명 TTL로 등록). 쿠키 정책·전달 채널은 그대로다. 상세: [[REDIS-TOKEN]].

## 6. 관심사 분리 — TokenPair

서비스는 토큰을 **생성**만 하고, **전달 매체(본문 vs 쿠키)는 컨트롤러**가 정한다.

```java
// 서비스 내부 표현 — DTO 아님
public record TokenPair(String accessToken, String refreshToken,
                        long accessTokenValiditySeconds, long refreshTokenValiditySeconds) {}
```

> `AuthService`는 `TokenPair`(두 토큰 + 만료)를 반환할 뿐, 그걸 본문에 넣을지 쿠키에 넣을지 모른다. `AuthController`가 access는 본문에, refresh는 쿠키에 배치한다. **토큰 생성 책임(Service)과 HTTP 표현 책임(Controller)의 분리** — 단계 5의 설계 포인트.

## 7. curl로 확인

```bash
# 로그인 → 쿠키 저장(-c), 응답 본문엔 access만
curl -i -c cookie.txt -X POST http://localhost:8090/api/v1/auth/login \
  -H "Content-Type: application/json" -d '{"username":"alice","password":"password123"}'
#   Set-Cookie: refreshToken=...; HttpOnly; SameSite=Strict; Path=/api/v1/auth
#   본문: {"accessToken":"...","tokenType":"Bearer","expiresIn":3600}   ← refresh 없음

# 재발급 → 쿠키 전송(-b), 본문 없이 호출
curl -b cookie.txt -X POST http://localhost:8090/api/v1/auth/reissue
#   {"accessToken":"<새 토큰>", ...}

# 쿠키 없이 재발급 → 401
curl -X POST http://localhost:8090/api/v1/auth/reissue
#   {"code":"INVALID_REFRESH_TOKEN", ...}

# 로그아웃 → 쿠키 만료 + DB 삭제
curl -i -b cookie.txt -X POST http://localhost:8090/api/v1/auth/logout
#   Set-Cookie: refreshToken=; Max-Age=0; ...
```

## 8. 핵심 요약 한 장

```
┌────────────────────────────────────────────────────────────────────┐
│ 단계 4 약점: refresh를 본문으로 → 클라가 localStorage 보관 → XSS 위험 │
│ 단계 5 해법: refresh를 httpOnly 쿠키로 → JS가 못 읽음 → XSS 차단      │
│                                                                     │
│ 쿠키 속성:                                                          │
│   HttpOnly  JS 접근 차단 (핵심)                                     │
│   Secure    HTTPS 전용 (로컬 false, 운영 true)                      │
│   SameSite  Strict — CSRF 면 축소                                   │
│   Path      /api/v1/auth — 노출 면적 최소화                         │
│                                                                     │
│ 비대칭:  access = 본문(메모리, Bearer 헤더)                         │
│          refresh = httpOnly 쿠키(브라우저 자동 동봉)                │
│                                                                     │
│ 코드:  ResponseCookie(만들기) / @CookieValue(읽기)                  │
│        TokenPair — 생성은 Service, 전달 매체는 Controller           │
└────────────────────────────────────────────────────────────────────┘
```

---

## 부록. 자주 나오는 질문 (FAQ)

| 질문 | 답변 요약 |
|------|-----------|
| access token도 쿠키에 두면 안 되나? | access는 매 요청 Authorization 헤더에 실어야 해서 JS가 읽어야 함 → 본문/메모리. 짧은 수명이라 위험 제한적. |
| HttpOnly면 JS가 못 읽는데 어떻게 보내나? | 브라우저가 자동으로 동봉한다. JS가 만질 필요 자체가 없다. |
| 왜 Path=/api/v1/auth? | refresh 쿠키가 일반 API 요청엔 안 실리고 reissue/logout에만 전송돼 노출이 줄어든다. |
| 로컬에서 Secure=true면? | HTTP라 브라우저/curl이 쿠키를 안 보내 재발급이 깨진다. 로컬 false, 운영 true. |
| SameSite=Strict면 다른 도메인 프론트는? | 크로스 오리진이면 None+Secure가 필요. 같은 오리진 강의 환경은 Strict로 충분. |
| CSRF는 안 위험한가? | 쿠키는 자동 전송이라 CSRF 면이 생기지만 SameSite로 완화. 더 엄격히는 CSRF 토큰 병행(후속 주제). |
