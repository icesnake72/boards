# 단계 7 작업 순서 — 카카오 OAuth2 (진행 현황 + 남은 작업)

**작성일**: 2026-07-03
**브랜치**: `step7-oauth2-kakao` (step6-method-security에서 분기)
**목적**: 무엇을 어떤 순서로 하는지, 지금 어디까지 왔는지, 다음에 무엇을 해야 하는지의 단일 기준 문서

---

## 1. 전체 작업 순서 (강의용 코딩 순서이기도 하다)

의존성 순서대로 A → E. 각 국면이 끝날 때마다 컴파일이 통과하도록 설계했다.

### A. 코드 밖 준비 — 카카오 개발자 콘솔
| # | 작업 | 상태 |
|---|------|------|
| 1 | 앱 생성, REST API 키 확인 | ✅ (yaml에 반영됨) |
| 2 | Redirect URI 등록: `http://localhost:8090/api/oauth/kakao/callback` | ⚠️ **콘솔에서 확인 필요** — yaml과 한 글자라도 다르면 KOE006 |
| 3 | Client Secret 생성 + 상태 "사용함" | ⚠️ **콘솔에서 확인 필요** |
| 4 | 동의 항목: 닉네임 ON, 이메일은 비즈 앱 필요(없어도 코드가 처리) | ⚠️ 확인 필요 |

### B. 설정과 도메인 확장 (외부 의존 없음 — 먼저 완성)
| # | 작업 | 파일 | 상태 |
|---|------|------|------|
| 1 | yaml에 카카오 엔드포인트 3종 추가 | `application.yaml` | ✅ |
| 2 | `AuthProvider` enum (LOCAL/KAKAO) | `user/AuthProvider.java` | ✅ |
| 3 | User에 provider/providerId + 복합 UNIQUE | `user/User.java` | ✅ |
| 4 | `findByProviderAndProviderId` | `user/UserRepository.java` | ✅ |
| 5 | ErrorCode 2종 추가 | `global/exception/ErrorCode.java` | ✅ |
| 6 | `@ConfigurationPropertiesScan` | `BoardApplication.java` | ✅ |
| 7 | configuration-processor 의존성 | `build.gradle` | ✅ |

### C. 카카오 통신 계층 (안 → 밖 순서)
| # | 작업 | 파일 | 상태 |
|---|------|------|------|
| 1 | 프로퍼티 record 바인딩 | `auth/oauth/KakaoOAuthProperties.java` | ✅ |
| 2 | 응답 DTO (snake_case, null-safe) | `auth/oauth/dto/Kakao*.java` | ✅ |
| 3 | RestClient 통신 (URL 생성/토큰 교환/사용자 조회) | `auth/oauth/KakaoOAuthClient.java` | ✅ |

### D. 비즈니스 로직과 HTTP 진입점
| # | 작업 | 파일 | 상태 |
|---|------|------|------|
| 1 | `AuthService.issueTokenPair(User)` 추출 (로컬/소셜 공용) | `auth/AuthService.java` | ✅ |
| 2 | find-or-create + 토큰 발급 | `auth/oauth/KakaoOAuthService.java` | ✅ |
| 3 | `/login`(state+302), `/callback`(검증+응답) | `auth/oauth/KakaoOAuthController.java` | ✅ |
| 4 | `/api/oauth/**` permitAll | `global/config/SecurityConfig.java` | ✅ |

### E. 검증과 문서
| # | 작업 | 상태 |
|---|------|------|
| 1 | 서비스 통합 테스트 5케이스 (client만 mock) | ✅ `KakaoOAuthServiceTest` |
| 2 | 컨트롤러 단위 테스트 5케이스 (state/쿠키) | ✅ `KakaoOAuthControllerTest` |
| 3 | 전체 테스트 회귀 확인 — **58개 전부 통과** | ✅ |
| 4 | 강의 문서 | ✅ `OAUTH2-KAKAO.md` |

---

## 2. 남은 작업 (다음 세션에서 이어서)

우선순위 순:

1. ~~[필수] 카카오 콘솔 대조~~ ✅ 완료 (2026-07-03) — Redirect URI 미등록으로 **KOE006**을 실제로 밟고 등록해 해결. 에러 페이지가 "사용한 리다이렉트 URI"를 그대로 보여주므로 콘솔 등록값과 눈으로 대조하면 된다.
2. ~~[필수] 실 브라우저 E2E 테스트~~ ✅ 완료 (2026-07-03) — 전 구간 검증:
   - `/login` → 302 → 카카오 로그인/동의 → `/callback` → `{accessToken, Bearer, 3600}` 응답
   - 발급 JWT로 `/api/v1/profiles/me` 200 (토큰 없으면 401)
   - DB: `users`에 `kakao_{회원번호}` / KAKAO / provider_id 저장, 프로필 닉네임·이메일 수신 확인
   - httpOnly refresh 쿠키로 `POST /api/v1/auth/reissue` 200 (재발급 동작), `document.cookie`에서 refreshToken 안 보임(httpOnly 확인)
3. ~~[필수] 기존 MySQL 데이터 마이그레이션~~ ✅ 완료 (2026-07-03) — **실측 결과 주의**: Hibernate 6가 `@Enumerated(STRING)`을 MySQL 네이티브 `ENUM('KAKAO','LOCAL')` 컬럼으로 만들고, ALTER 시 기존 row가 NULL이 아니라 **첫 enum 값('KAKAO')으로 채워졌다**. 실제 필요한 SQL:
   ```sql
   UPDATE users SET provider = 'LOCAL' WHERE provider_id IS NULL;
   ```
   (providerId가 없으면 로컬 가입자다 — 이 조건이 안전하다)
4. ~~[권장] 실키 env 전환~~ ✅ 완료 (2026-07-04) — `.env` + `spring.config.import` 로드, Secret 재발급, yaml 기본값 제거, 기동 시점 검증(fail-fast) 추가.
5. ~~[권장] CURL-TEST.md에 단계 7 절 추가~~ ✅ 완료 (2026-07-04) — §10: 302/state 관찰, 브라우저 로그인, 실패 케이스 4종(모두 실행 검증), httpOnly 재발급.
6. ~~[선택] 강의 PPT 제작~~ → **코딩 순서 문서로 대체** (2026-07-04) — PPT 대신 `OAUTH2-KAKAO.md` **§7 "기능 추가 코딩 순서 (단계 6 → 7)"** 를 추가했다. Phase 0~G(콘솔 → 설정 → 도메인 → 재사용 추출 → 외부 경계 → 조립 → 진입점 → 검증)와 "왜 이 순서인가", 기능 추가 일반 원칙 6가지를 정리. 아래 §1 표는 진행 현황 체크리스트, 강의용 서사는 §7이 기준이다.
7. **[선택] 커밋 분리** — 기존 관례대로: `feat: 카카오 OAuth2 로그인 추가 (단계 7)` → `docs: 단계 7 강의 문서 추가`.

---

## 3. 이 단계에서 내린 설계 결정 (왜 이렇게 했나)

| 결정 | 이유 |
|------|------|
| oauth2-client 라이브러리 대신 수동 구현 | 강의 철학(단계 2 수동 JWT → 단계 3 표준과 동일 패턴). yaml의 커스텀 프로퍼티 구조와도 일치. 표준 전환은 후속 주제로 남김. |
| state를 세션이 아닌 쿠키에 | 단계 3부터 서버는 STATELESS. 세션을 되살리지 않고 쿠키+대조로 CSRF 방어. |
| state 쿠키만 SameSite=Lax | 콜백이 카카오發 크로스 사이트 이동이라 Strict이면 쿠키가 안 실린다. refresh 쿠키는 Strict 유지. |
| 소셜 식별을 (provider, providerId)로 | 이메일은 미동의 시 null + 변경 가능. 카카오 회원번호만 불변. |
| 이메일 충돌 시 대체 이메일(별도 계정) | 소유 확인 없는 자동 연동은 계정 탈취 벡터. 명시적 계정 연동은 후속 주제. |
| 소셜 password = 랜덤 UUID 해시 | NOT NULL 제약 충족 + password 로그인 원천 차단. 스키마 변경 최소화. |
| 카카오 통신을 KakaoOAuthClient 한 곳에 | 외부 의존을 경계 하나로 모아 그것만 mock하면 나머지 전부 실제 검증 가능. |

---

## 4. 단계 8 예고 (이번에 미룬 것)

- **Refresh Token Rotation** — 재발급마다 refresh도 새로 발급 + 재사용 탐지 (`REFRESH-TOKEN.md`가 예고한 후속 주제)
- 후보: 로컬-소셜 계정 연동, 네이버/구글 추가 → `spring-boot-starter-oauth2-client` 표준 전환
