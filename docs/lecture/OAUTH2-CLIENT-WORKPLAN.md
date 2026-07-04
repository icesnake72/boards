# 단계 8 작업 순서 — OAuth 표준화 (spring-boot-starter-oauth2-client)

**작성일**: 2026-07-04
**브랜치**: `step8-oauth2-client` (step7-oauth2-kakao에서 분기)
**목적**: 단계 7의 수동 OAuth 구현을 Spring Security 표준 부품으로 대체하는 작업의 순서·현황·이유의 단일 기준 문서

---

## 0. 전환 전략 — "병행 후 제거" (단계 2→3과 다른 점)

단계 2→3은 같은 자리를 표준으로 **대체**하는 빅뱅이라 D 단계까지 컴파일이 깨졌다.
이번에는 표준 경로(`/oauth2/authorization/kakao`)를 수동 경로(`/api/oauth/kakao/login`) **옆에 나란히 세운다** — URL이 달라 충돌하지 않으므로:

- 매 Phase 끝에서 컴파일·기존 테스트가 항상 green
- 두 경로를 **모두 살려둔 채 실 테스트로 비교** 가능 (강의 시연 포인트)
- 표준 경로가 검증된 **후에야** 수동 구현을 제거 (strangler 패턴)

수동 ↔ 표준 부품 대응 (무엇이 무엇으로 바뀌나):

| 역할 | 단계 7 수동 구현 | 단계 8 표준 부품 |
|------|-----------------|-----------------|
| 로그인 시작 (302 + state) | `KakaoOAuthController.login()` | `OAuth2AuthorizationRequestRedirectFilter` (자동) |
| state 저장/검증 | 직접 만든 state 쿠키 | `AuthorizationRequestRepository` (기본은 세션 — **우리는 쿠키 구현 필요**) |
| 토큰 교환 | `KakaoOAuthClient.requestToken` | `OAuth2LoginAuthenticationProvider` (자동) |
| 사용자 조회 | `KakaoOAuthClient.fetchUser` | `DefaultOAuth2UserService.loadUser` (자동) |
| find-or-create | `KakaoOAuthService` | **`CustomOAuth2UserService`** (로직 이사 — 단계 3의 `CustomUserDetailsService`에 대응) |
| 성공 응답 (JWT + 쿠키) | `KakaoOAuthController.callback()` | **`OAuth2LoginSuccessHandler`** |
| 실패 응답 (401 JSON) | callback의 예외 → GlobalExceptionHandler | **`OAuth2LoginFailureHandler`** |
| 설정 | `app.oauth.kakao.*` (커스텀) | `spring.security.oauth2.client.*` (표준) |

**변하지 않는 것**: `AuthService.issueTokenPair`, `RefreshCookieFactory`, `User`(provider/providerId), find-or-create 정책(랜덤 password, 대체 이메일, 닉네임 충돌 처리) — 단계 7에서 만든 도메인 설계는 그대로다.

---

## 1. 전체 작업 순서

### Phase 0 — 코드 밖 준비 (카카오 콘솔)
| # | 작업 | 상태 |
|---|------|------|
| 1 | **새 Redirect URI 등록**: `http://localhost:8090/login/oauth2/code/kakao` (라이브러리 표준 경로) | ⚠️ **사용자 작업 필요** |
| 2 | 기존 URI(`/api/oauth/kakao/callback`)는 병행 기간 동안 유지, Phase F 후 제거 가능 | — |

### Phase A — 설정 (표준 프로퍼티)
| # | 작업 | 상태 |
|---|------|------|
| 1 | `spring-boot-starter-oauth2-client` 의존성 | ✅ |
| 2 | `spring.security.oauth2.client.registration.kakao` + `provider.kakao` (카카오는 비표준 제공자라 provider 블록 직접 기술, `user-name-attribute: id`) | ✅ |
| 3 | 테스트 yaml에 더미 registration/provider (없으면 `oauth2Login()` 구성 시 컨텍스트 기동 실패) | ✅ |

### Phase B — 표준 부품 만들기 (수동 경로와 병행, 순서 = 의존 역방향)
| # | 작업 | 파일 | 상태 |
|---|------|------|------|
| 1 | 쿠키 기반 state 저장소 — STATELESS라 세션 기본값 사용 불가. 단계 7 수동 state 쿠키의 표준판 | `CookieOAuth2AuthorizationRequestRepository` | ✅ |
| 2 | find-or-create 이사 (단계 7 `KakaoOAuthService` 로직 재사용) | `CustomOAuth2UserService` | ✅ |
| 3 | 성공 응답: providerId로 사용자 조회 → `issueTokenPair` → 본문/쿠키 배치 | `OAuth2LoginSuccessHandler` | ✅ |
| 4 | 실패 응답: 401 `OAUTH_LOGIN_FAILED` JSON (RestAuthenticationEntryPoint 패턴) | `OAuth2LoginFailureHandler` | ✅ |

### Phase C — 연결
| # | 작업 | 상태 |
|---|------|------|
| 1 | `SecurityConfig`에 `.oauth2Login(...)` — 4개 부품 조립. URL은 표준 기본값 유지(`/oauth2/authorization/kakao`, `/login/oauth2/code/kakao`) | ✅ |

### Phase D — 검증 1 (자동)
| # | 작업 | 상태 |
|---|------|------|
| 1 | 신규 테스트: `CustomOAuth2UserServiceTest`(upsert 4케이스), `CookieOAuth2AuthorizationRequestRepositoryTest`(save/load/remove/불량쿠키) | ✅ |
| 2 | 전체 테스트 회귀 (수동 경로 테스트 포함 모두 green이어야 함) | ✅ |
| 3 | curl로 표준 경로 302 + oauthRequest 쿠키 관찰 (`/oauth2/authorization/kakao`) — 콘솔 등록 전에도 가능 | ✅ |

### Phase E — 검증 2 (실 브라우저 E2E, Phase 0-1 완료 후)
| # | 작업 | 상태 |
|---|------|------|
| 1 | 브라우저에서 `/oauth2/authorization/kakao` → 카카오 로그인 → JSON 응답/DB/reissue 확인 | ✅ (2026-07-04) — 콜백 `/login/oauth2/code/kakao` 도착, 기존 사용자 재사용(중복 없음), refresh 갱신, httpOnly reissue 200 |
| 2 | 수동 경로(`/api/oauth/kakao/login`)도 여전히 동작하는지 비교 시연 | ✅ — 두 경로 병행 동작 확인 (state 형식 차이: 수동 UUID vs 표준 base64url) |

### Phase F — 수동 구현 제거 (⚠️ 사용자 확인 후 진행)
| # | 작업 | 상태 |
|---|------|------|
| 1 | 삭제: `KakaoOAuthClient/Controller/Service/Properties`, `dto/KakaoTokenResponse`, `dto/KakaoUserResponse`, `RestClientConfig` (7파일) + 관련 테스트 3파일 | ⬜ |
| 2 | `SecurityConfig`의 `/api/oauth/**` permitAll 제거, yaml의 `app.oauth.kakao.*` 제거 | ⬜ |
| 3 | 전체 테스트 + 실 E2E 재확인 | ⬜ |

### Phase G — 문서
| # | 작업 | 상태 |
|---|------|------|
| 1 | 강의 문서 `OAUTH2-CLIENT.md` (부품 대응, 자동으로 바뀐 것/여전히 우리가 만드는 것, FAQ) | ⬜ |
| 2 | CURL-TEST.md 단계 8 반영 (경로 변경) | ⬜ |

---

## 2. 설계 결정 (왜 이렇게 하나)

| 결정 | 이유 |
|------|------|
| 병행 후 제거 (strangler) | URL이 달라 공존 가능. 표준 경로 검증 전까지 동작하는 경로를 잃지 않는다. 매 Phase green. |
| state 저장을 쿠키 기반 `AuthorizationRequestRepository`로 직접 구현 | 기본값(세션)은 STATELESS 설계 위반. "표준을 써도 우리 환경에 맞는 어댑터 하나는 만들게 된다"는 강의 포인트. |
| 쿠키에 JDK 직렬화 대신 필요한 필드만 JSON 저장 | 클라이언트가 조작 가능한 쿠키를 역직렬화하는 것은 insecure deserialization(CWE-502) 위험. state·clientId 등 필드만 담아 재조립한다. |
| SuccessHandler에서 providerId(`authentication.getName()`)로 재조회 | 커스텀 principal 클래스를 만들지 않아 부품 수 최소화(학습 난이도). `user-name-attribute: id` 덕에 getName() = 카카오 회원번호 = 단계 7의 providerId와 동일 값. |
| URL을 표준 기본값으로 | `/oauth2/authorization/{id}`, `/login/oauth2/code/{id}`가 업계 표준 관례 — 커스텀 URL은 표준화 취지에 역행. 콜백 URI가 바뀌므로 콘솔 재등록 필요. |
| `client-authentication-method: client_secret_post` | 카카오 token endpoint는 Basic 헤더가 아니라 form 본문의 client_id/client_secret을 요구한다. |

---

## 3. 리스크 / 주의

- **콘솔 Redirect URI 미등록** 상태로 E2E를 시도하면 단계 7과 동일한 KOE006 — Phase 0-1이 선행 조건.
- **scope**: 수동 구현은 scope 파라미터를 아예 안 보냈지만(동의된 항목 기본 제공), 표준 클라이언트는 명시한 scope를 요청한다. 콘솔 동의 항목에 없는 scope를 넣으면 KOE에러 — `profile_nickname`부터 시작하고 email은 E2E에서 확인 후 조정.
- **Phase F 이후 fail-fast 소실**: `KakaoOAuthProperties`(기동 검증)가 삭제되면 `.env` 누락 시 표준 프로퍼티에 `${...}` 문자열이 그대로 들어간다. 필요 시 등가 검증을 추가할지 Phase F에서 결정.
