# Refresh Token — Access/Refresh 분리와 DB 저장 (단계 4)

**과정명**: 강의용 Spring Boot 게시판 — 단계 4 (refresh token 도입)
**대상**: 단계 3(Spring Security 표준 JWT)을 마친 수강생
**브랜치/태그**: `step4-refresh-token` / `v-step4-refresh`
**관련 코드**: `auth/RefreshToken.java`, `auth/RefreshTokenRepository.java`, `auth/AuthService.java`
**선수 지식**: [JWT-AUTH.md](JWT-AUTH.md), [SPRING-SECURITY-STANDARD.md](SPRING-SECURITY-STANDARD.md)

---

## 학습 목표

이 문서를 끝내면 수강생은:

- 왜 access token 하나로는 부족한지, refresh token이 푸는 문제를 설명할 수 있다
- "stateless access + stateful refresh" 하이브리드의 의미를 안다
- refresh token을 DB에 저장하는 이유(검증·취소)를 설명할 수 있다
- 로그인/재발급/로그아웃 흐름을 코드로 따라갈 수 있다

---

## 1. 문제 — access token 하나의 딜레마

access token(JWT)은 **stateless**라 서버가 상태를 안 가진다. 좋지만 약점이 있다:

- **짧게 두면**: 1시간마다 만료 → 사용자가 자꾸 다시 로그인해야 함 (UX 나쁨)
- **길게 두면**: 탈취 시 만료까지 오래 유효, 강제 무효화도 어려움 (보안 나쁨)

> 짧은 수명(보안)과 긴 로그인 유지(편의)를 **동시에** 원한다 — 토큰 하나로는 불가능.

## 2. 해법 — 역할이 다른 두 토큰

| | access token | refresh token |
|--|-------------|---------------|
| 용도 | 매 요청 인증 | access token **재발급에만** |
| 수명 | 짧게 (1시간) | 길게 (14일) |
| 형식 | JWT (서명, stateless) | **opaque UUID** |
| 저장 | 서버에 저장 안 함 | **서버 DB에 저장** (stateful) |
| 노출 빈도 | 매 요청 | 재발급 때만 (적음) |

흐름:

```
로그인 → access(1h) + refresh(14d) 함께 발급
   │
   ├ 평소: access token으로 API 호출
   │
   └ access 만료(1h 후): refresh token으로 /reissue → 새 access token
                          (14일 동안 재로그인 없이 반복)
```

> **왜 refresh는 opaque UUID인가?** access는 stateless 검증(서명)이 목적이라 JWT가 맞지만, refresh는 **서버가 DB에 저장해 직접 검증·취소**하는 게 목적이다. 그래서 의미 없는 랜덤 문자열(UUID)이면 충분하고, DB가 진실의 원천이 된다.

## 3. 왜 refresh token을 DB에 저장하나

서버 DB의 refresh token은 다음을 가능하게 한다:

| 능력 | 설명 |
|------|------|
| **검증** | 클라이언트가 제시한 refresh가 "내가 발급한 것"인지 DB 조회로 확인 |
| **만료 확인** | `expiresAt` 비교 |
| **취소(revoke)** | 로그아웃·보안 사고 시 DB에서 지우면 **즉시 무효화** |

> 단계 3까지는 로그아웃이 사실상 무의미했다(stateless라 서버에 지울 게 없음). refresh token을 DB에 두면서 **로그아웃이 진짜 무효화**가 됐다.

## 4. 클래스 — RefreshToken 엔티티

`auth/RefreshToken.java`:

```java
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends BaseTimeEntity {

  @Id @GeneratedValue(strategy = IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)   // 한 사용자당 하나
  private Long userId;

  @Column(nullable = false, unique = true)    // UUID 저장
  private String token;

  @Column(nullable = false)
  private LocalDateTime expiresAt;

  public void update(String token, LocalDateTime expiresAt) {  // 로그인 시 교체
    this.token = token;
    this.expiresAt = expiresAt;
  }

  public boolean isExpired() {
    return expiresAt.isBefore(LocalDateTime.now());
  }
}
```

| 필드 | 의미 |
|------|------|
| `userId` (unique) | 한 사용자당 refresh token 하나 — 재로그인 시 교체 |
| `token` (unique) | opaque UUID 문자열 |
| `expiresAt` | 만료 시각 (14일 뒤) |

> 평문 UUID 저장은 강의 단순화다. **운영에선 해시 저장을 권장**(DB 유출 시 토큰 그대로 노출 방지) — 주석에 명시돼 있다.

`RefreshTokenRepository`:

```java
Optional<RefreshToken> findByToken(String token);    // 재발급/로그아웃 시 조회
Optional<RefreshToken> findByUserId(Long userId);    // 로그인 시 기존 토큰 교체
```

## 5. 흐름 — 코드로 보기

### 5-1. 로그인 — 두 토큰 발급

`AuthService.login` (핵심):

```java
@Transactional   // refresh를 저장하므로 쓰기 트랜잭션
public TokenPair login(LoginRequest request) {
  // (단계 3) AuthenticationManager로 인증
  Authentication authentication = authenticationManager.authenticate(...);
  CustomUserDetails principal = (CustomUserDetails) authentication.getPrincipal();

  String accessToken = tokenProvider.createToken(principal.getUsername());  // JWT
  String refreshToken = issueRefreshToken(principal.getId());               // UUID + DB 저장
  return new TokenPair(accessToken, refreshToken, ...);
}

private String issueRefreshToken(Long userId) {
  String token = UUID.randomUUID().toString();
  LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(refreshTokenValiditySeconds);
  refreshTokenRepository.findByUserId(userId)
      .ifPresentOrElse(
          existing -> existing.update(token, expiresAt),   // 있으면 교체
          () -> refreshTokenRepository.save(new RefreshToken(userId, token, expiresAt)));
  return token;
}
```

> **한 사용자당 하나**: `findByUserId`로 기존 토큰을 찾아 있으면 `update`(교체), 없으면 새로 저장. 재로그인하면 이전 refresh token은 무효가 된다.

### 5-2. 재발급 — refresh로 새 access만

`AuthService.reissue`:

```java
@Transactional
public TokenPair reissue(String refreshToken) {
  RefreshToken stored = refreshTokenRepository.findByToken(refreshToken)
      .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN));  // 없음 → 401
  if (stored.isExpired()) {
    refreshTokenRepository.delete(stored);
    throw new UnauthorizedException(ErrorCode.EXPIRED_REFRESH_TOKEN);                  // 만료 → 401
  }
  User user = userRepository.findById(stored.getUserId()).orElseThrow(...);
  String newAccessToken = tokenProvider.createToken(user.getUsername());
  // access token만 새로. refresh는 회전하지 않고 그대로.
  return new TokenPair(newAccessToken, refreshToken, ...);
}
```

> **회전(rotation) 미적용**: 재발급 때 refresh token은 그대로 두고 access token만 새로 만든다. (회전 = 재발급마다 refresh도 새로 발급 → 탈취 탐지에 유리, 후속 주제)

### 5-3. 로그아웃 — 서버 측 삭제

```java
@Transactional
public void logout(String refreshToken) {
  refreshTokenRepository.findByToken(refreshToken)
      .ifPresent(refreshTokenRepository::delete);   // 있으면 삭제 (없어도 통과 — idempotent)
}
```

> 이제 로그아웃이 **의미를 가진다**: DB에서 refresh token을 지우면 그 토큰으로는 더 이상 재발급할 수 없다.

## 6. 엔드포인트 (단계 4 — 본문 전달)

| 메서드 | 경로 | 입력 | 출력 |
|--------|------|------|------|
| POST | /api/v1/auth/login | LoginRequest | `{accessToken, refreshToken, ...}` |
| POST | /api/v1/auth/reissue | `{refreshToken}` | `{accessToken, ...}` |
| POST | /api/v1/auth/logout | `{refreshToken}` | 204 |

> `/auth/**`는 permitAll이다 — **refresh token 자체가 자격증명**이므로 재발급에 별도 인증이 필요 없다.
>
> ⚠️ 단계 4는 refresh token을 **JSON 본문**으로 주고받는다. 클라이언트가 직접 저장해야 해서 보안상 약점(localStorage 보관 → XSS 위험)이 있다. → **단계 5에서 httpOnly 쿠키로 개선**한다.

## 7. 핵심 요약 한 장

```
┌────────────────────────────────────────────────────────────────────┐
│ 문제: access 짧으면 잦은 재로그인, 길면 보안 위험                    │
│ 해법: 역할이 다른 두 토큰                                            │
│                                                                     │
│   access token   JWT, 1시간, stateless, 매 요청 인증                │
│   refresh token  UUID, 14일, DB 저장(stateful), 재발급에만           │
│                                                                     │
│ 로그인:  access + refresh 발급, refresh는 DB 저장(사용자당 하나)     │
│ 재발급:  POST /reissue + refresh → 새 access만 (회전 X)             │
│ 로그아웃: DB에서 refresh 삭제 → 진짜 무효화                          │
│                                                                     │
│ DB 저장 이유: 검증 · 만료확인 · 취소(revoke)                        │
│ 단계 4는 본문 전달 → 단계 5에서 httpOnly 쿠키로 개선                 │
└────────────────────────────────────────────────────────────────────┘
```

---

## 부록. 자주 나오는 질문 (FAQ)

| 질문 | 답변 요약 |
|------|-----------|
| 왜 refresh는 JWT가 아니라 UUID? | 서버가 DB에 저장해 검증·취소하는 게 목적이라 의미 없는 랜덤이면 충분. DB가 진실의 원천. |
| access도 DB에 저장하면? | 그러면 stateless 이점이 사라진다. access는 서명만으로 검증(무상태), refresh만 저장. |
| 재발급 때 refresh도 새로 줘야 하나? | 회전(rotation)을 적용하면 보안에 유리하나, 단계 4는 단순화를 위해 미적용(후속 주제). |
| 한 사용자가 여러 기기로 로그인하면? | 현재는 사용자당 하나라 마지막 로그인만 유효. 다중 기기는 (userId, deviceId)로 여러 개 저장하도록 확장. |
| refresh token이 탈취되면? | DB에서 삭제(로그아웃/강제 무효화)하면 즉시 차단. 이게 DB 저장의 핵심 이점. |
| 왜 평문 UUID 저장? | 강의 단순화. 운영에선 해시로 저장해 DB 유출 시 원문 노출을 막는다. |
