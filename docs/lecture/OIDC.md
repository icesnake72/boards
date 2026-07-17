# OIDC — OAuth2 위의 인증 계층 (단계 9)

- **과정명**: 강의용 Spring Boot 게시판 — 단계 9 (OIDC 전환)
- **대상**: 단계 8(OAuth 표준화)을 마친 수강생
- **브랜치**: `step9-oidc`
- **관련 코드**: `auth/oauth2/CustomOidcUserService`(신규), `auth/oauth2/CustomOAuth2UserService`, `auth/oauth2/OAuth2LoginSuccessHandler`, `global/config/SecurityConfig`, `application.yaml`
- **선수 지식**: [OAUTH2-CLIENT.md](OAUTH2-CLIENT.md) — 특히 §5-5의 "openid 함정" 각주, [JWT-AUTH.md](JWT-AUTH.md) — 우리 JWT의 서명/검증 구조
- **검증 상태**: 전체 테스트 75개 green + 실 브라우저 E2E 확인 (2026-07-18)

---

## 학습 목표

이 문서를 끝내면 수강생은:

- 단계 8의 "openid를 넣으면 우리 서비스를 안 탄다" 함정을 **OIDC라는 별도 프로토콜의 존재로** 설명할 수 있다
- OAuth2(인가)와 OIDC(인증)의 관계를 "OAuth2 위의 인증 계층"으로 정확히 말할 수 있다
- id_token(외부 JWT)과 우리 access token(내부 JWT)이 **누가 서명하고 누가 검증하는지** 구분할 수 있다
- Spring Security가 openid scope 유무로 **담당 서비스를 바꿔 부르는** 지점을 안다
- OidcUser와 OAuth2User의 관계, `getName()`이 여전히 sub인 이유를 안다

---

## 한눈에 보기 — 3분 요약

바쁘면 이 섹션만 읽어도 된다. 상세는 §1부터.

**OIDC란**: 우리가 카카오/구글 "로그인"에 써 온 OAuth2는 원래 **인가(권한 위임)** 프로토콜이다 — "이 앱이 내 정보를 읽어도 된다"까지만 보장하고, "지금 온 사람이 누구인가"는 보장하지 않는다. OIDC(OpenID Connect)는 OAuth2 위에 얹은 **인증 계층**으로, 토큰 응답에 **id_token**(제공자가 서명한 JWT 신분증)을 하나 더 실어 보낸다. 우리는 그 서명을 검증하는 것만으로 "구글이 보증한 사용자 정보"를 별도 조회 없이 얻는다.

**우리의 구현** — 전부 3곳:

| 변경 | 파일 | 내용 |
|------|------|------|
| scope 1줄 | `application.yaml` | 구글 scope에 `openid` 추가 → 구글 로그인이 OIDC 경로로 전환 |
| 신규 1클래스 | `CustomOidcUserService` | 표준 `OidcUserService`에 위임해 검증된 id_token을 받고, 기존 `upsertUser`(find-or-create) 재사용 |
| 연결 1줄 | `SecurityConfig` | `.userInfoEndpoint`에 `.oidcUserService(...)` 추가 — 카카오(OAuth2)와 구글(OIDC) 병존 |

**처리 시퀀스** — 단계 8과 뼈대는 같고, ⑤~⑥(id_token 검증이 userinfo 조회를 대체)만 다르다:

```mermaid
sequenceDiagram
    participant B as 브라우저
    participant F as Spring Security 필터<br/>(라이브러리 코드)
    participant U as 우리가 작성한 클래스<br/>(같은 서버 안)
    participant G as 구글

    B->>F: ① GET /oauth2/authorization/google
    F->>U: ② 인가 요청 저장 (쿠키 저장소 — 단계 8 그대로)
    F-->>B: ③ 302 → accounts.google.com (scope=openid profile email)
    B->>G: ④ 계정 선택/동의 후 콜백 (code, state)
    F->>G: ⑤ 토큰 교환 — 응답에 id_token(JWT)이 함께 온다
    F->>F: ⑥ id_token 서명·iss·aud·exp 검증 (라이브러리가 완료)
    F->>U: ⑦ CustomOidcUserService.loadUser<br/>검증된 claims(sub/email/name)로 upsertUser 재사용
    F->>U: ⑧ OAuth2LoginSuccessHandler (단계 8 그대로)
    U-->>B: ⑨ 200 {우리 accessToken} + refresh 쿠키
```

핵심 한 줄: **구글에게 "누구인지 물어보러 가는"(userinfo HTTP 호출) 대신, 구글이 서명해 준 신분증(id_token)을 검증해서 읽는다.** 나머지(state 쿠키, upsert 정책, JWT 발급)는 단계 8에서 만든 그대로다.

---

## 1. 왜 OIDC인가 — 단계 8의 복선 회수

단계 8 §5-5의 마지막 함정에는 이런 각주가 있었다:

> **함정 — openid scope**: 구글 기본 scope에 `openid`가 있는데, 이걸 넣으면 **OIDC 경로**로 빠져 `OidcUserService`가 담당하게 되고 우리 `CustomOAuth2UserService`는 호출되지 않는다. 순수 OAuth2로 통일하려고 `scope: profile,email`만 명시했다. (OIDC 전환은 후속 주제)

그때 우리는 openid를 **일부러 뺐다**. "OIDC가 뭔지 아직 안 배웠으니 순수 OAuth2로 통일하자"는 유예였다. 이번 단계는 그 유예를 회수한다 — openid를 다시 넣고, 왜 담당자가 바뀌었는지, 그 새 담당자에 우리 로직을 어떻게 연결하는지를 배운다.

**용어 정리 — 자주 헷갈리는 지점**:

| 프로토콜 | 답하는 질문 | 표준 산출물 |
|----------|-------------|-------------|
| OAuth 2.0 | "이 클라이언트가 당신 리소스에 접근해도 되는가?" (**인가/authorization**) | access token |
| OIDC | "이 사람은 누구인가?" (**인증/authentication**) | id_token (+ access token) |

우리가 단계 7·8에서 카카오/구글로 **로그인**했을 때 실제로 쓴 것은 OAuth2였다 — "인가 프로토콜을 인증에 유용(流用)"한 것이다. 이 유용에는 흔한 실수가 뒤따른다(access token을 "그 사람이다"라는 증명으로 오해, userinfo 응답이 다른 사람 것일 가능성 등). OIDC는 그 유용을 **표준화**해 "인증에 쓰려면 이 규격을 지켜라"를 정한 계층이다.

> [!IMPORTANT]
> OIDC는 OAuth2를 대체하지 않는다. **OAuth2 위에 얹은 인증 계층**이다 — 토큰 교환 흐름은 동일하고, 응답에 서명된 사용자 정보(id_token)가 하나 더 실려 온다.

---

## 2. id_token이 바꾸는 것 — 세 개의 JWT를 구분하기

우리 프로젝트에는 지금 JWT가 **세 종류** 등장하게 됐다. 혼동하지 않는 것이 이 단계의 절반이다.

| JWT | 발급자 | 검증자 | 언제 살아 있나 | 담긴 내용 |
|-----|--------|--------|----------------|-----------|
| 우리 access token (단계 2·3) | 우리 서버 (`JwtTokenProvider`) | 우리 서버 (`JwtAuthenticationFilter`) | 로그인 후 1시간 | userId, role |
| 우리 refresh token (단계 4·5) | 우리 서버 | 우리 서버 (`AuthService.reissue`) | 14일 | subject=userId |
| **구글 id_token (단계 9)** | **구글** | **우리 서버가 구글 JWK로** | 로그인 처리 순간만 | sub, email, name, iss, aud, exp, nonce |

id_token은 **우리에게 새로운 개념이 아니다** — 단계 2에서 손으로 만들고 단계 3에서 표준화한 그 JWT 구조 그대로다. 다만 **누가 서명하느냐가 다르다**: 지금까지의 우리 JWT는 우리가 서명하고 우리가 검증했지만, id_token은 **구글이 서명**하고 우리는 구글이 공개한 **JWK**(JSON Web Key)로 서명을 검증한다. 이 차이가 곧 OIDC가 해결하는 문제 — "이 사용자 정보가 정말 구글에서 온 것인가"를 별도 HTTP 호출 없이 서명 하나로 증명할 수 있다.

**access token vs id_token — 순수 OAuth2와의 대비**:

| | 순수 OAuth2 (단계 8 카카오) | OIDC (단계 9 구글) |
|---|-----|-----|
| 사용자 정보를 얻는 법 | 토큰 교환 후 별도 HTTP 호출 (userinfo endpoint) | 토큰 응답에 id_token으로 이미 실려 옴 |
| 사용자 정보의 신뢰 근거 | "userinfo 응답이 카카오 서버에서 왔다"는 TLS 신뢰 | id_token의 **디지털 서명** |
| 토큰의 형식 | 불투명 문자열(opaque) — 카카오만 해석 | JWT — 누구나 파싱, 서명 검증만 하면 됨 |

**중요 — 우리가 검증을 안 짜도 되는 이유**: `OidcAuthorizationCodeAuthenticationProvider`(라이브러리)가 우리 `CustomOidcUserService.loadUser`를 호출하기 **전에** id_token의 **서명·iss(구글)·aud(우리 client_id)·exp·nonce**를 모두 검증해 둔다. loadUser가 호출된 시점에는 "검증 통과된 id_token이 여기 있다"가 보장된 상태다 — 우리는 claims에서 값만 꺼내면 된다. 단계 3에서 표준화의 이득을 논한 그 문법이 여기서도 동일하다.

---

## 3. 경로 분기 — openid scope 한 줄이 담당자를 바꾼다

Spring Security의 oauth2Login은 토큰 응답을 받은 **직후** scope에 `openid`가 있는지를 본다. 그 한 줄로 아래 두 경로가 갈린다:

```mermaid
flowchart TD
  Start["콜백<br/>/login/oauth2/code/{id}"] --> Check{"scope에<br/>openid?"}
  Check -->|"없음<br/>(카카오: profile_nickname)"| A["OAuth2LoginAuthenticationProvider"]
  Check -->|"있음<br/>(구글: openid,profile,email)"| O["OidcAuthorizationCodeAuthenticationProvider"]
  A --> A2["DefaultOAuth2UserService.loadUser<br/>= userinfo HTTP 호출"]
  O --> O2["id_token 서명·iss·aud·exp·nonce 검증<br/>+ OidcUserService.loadUser"]
  A2 --> AU["CustomOAuth2UserService<br/>(우리 upsert)"]
  O2 --> OU["CustomOidcUserService<br/>(우리 upsert, 신규)"]
  AU --> H["OAuth2LoginSuccessHandler<br/>(공유)"]
  OU --> H
  H --> Done["우리 JWT + refresh 쿠키"]
```

**공유되는 것 / 나뉘는 것**:

| 컴포넌트 | 단계 8 (OAuth2) | 단계 9 (OIDC) |
|----------|-----------------|---------------|
| 콜백 필터 (`OAuth2LoginAuthenticationFilter`) | 공유 | 공유 |
| state 저장소 (`CookieOAuth2AuthorizationRequestRepository`) | 공유 | 공유 |
| 인증 Provider | `OAuth2LoginAuthenticationProvider` | `OidcAuthorizationCodeAuthenticationProvider` |
| 사용자 로딩 서비스 | `CustomOAuth2UserService` | **`CustomOidcUserService` (신규)** |
| SuccessHandler | 공유 | 공유 (`getName()`이 여전히 sub이므로 수정 없음) |
| FailureHandler | 공유 | 공유 |

즉 필터·저장소·핸들러는 그대로 두고, "사용자 로딩" 지점 한 곳에만 OIDC판 서비스를 하나 더 꽂으면 된다. `SecurityConfig`의 `.userInfoEndpoint(...)`가 **두 서비스를 동시에** 등록하는 그 조립 지점이다(§4-3).

---

## 4. 무엇을 바꿨나 — 전환 비용의 실측

단계 8 §5-5에서 구글 추가 비용을 실측했듯, 이번엔 "OAuth2 → OIDC 전환" 비용을 실측한다. **실제 diff 전부**:

### 4-1. yaml — scope 한 줄

```yaml
google:
  client-id: ${GOOGLE_CLIENT_ID}
  client-secret: ${GOOGLE_CLIENT_SECRET}
  # scope: profile,email  # 단계 9 처리에 의해 제거 — 단계 8은 openid를 빼서 OIDC 경로를 회피했었다
  scope: openid,profile,email
```

**교보재 포인트 — 왜 제거한 라인을 주석으로 남기나**: 지운 라인이 있던 자리에 "왜 여기서 무엇이 바뀌었는지"를 흔적으로 남겼다. 커밋 로그를 뒤지지 않아도 diff의 의미가 보인다. 이 프로젝트의 lecture 컨벤션이다 — 학습용이라 히스토리 자체가 교재이기 때문. 프로덕션 코드에서는 삭제가 정답이다.

### 4-2. `CustomOidcUserService` — 신규 1클래스

```java
@Service
@RequiredArgsConstructor
public class CustomOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

  private final CustomOAuth2UserService customOAuth2UserService;

  // 표준 OidcUserService에 위임 — id_token 검증/userinfo 병합은 이쪽 몫
  private OAuth2UserService<OidcUserRequest, OidcUser> delegate = new OidcUserService();

  // 테스트 전용 — 실제 구글 호출 없이 delegate를 stub으로 교체
  void setDelegate(OAuth2UserService<OidcUserRequest, OidcUser> delegate) {
    this.delegate = delegate;
  }

  @Override
  @Transactional
  public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
    OidcUser oidcUser = delegate.loadUser(userRequest);
    String registrationId = userRequest.getClientRegistration().getRegistrationId();
    // getAttributes() = id_token claims — 구글 userinfo와 같은 평면 sub/email/name이라 GOOGLE 분기 재사용
    customOAuth2UserService.upsertUser(registrationId, oidcUser.getAttributes());
    return oidcUser;
  }
}
```

**포인트 3개**:

- **상속 대신 위임(composition)** — 단계 8 `CustomOAuth2UserService`는 `DefaultOAuth2UserService`를 **extends**했지만 여기는 필드로 `OidcUserService`를 들고 있다. Oidc 쪽은 상속을 권장하지 않는 구조(final 여부/hook 부재)이고, 테스트에서 delegate를 갈아끼우기도 쉽다.
- **upsert 로직 재사용** — `CustomOAuth2UserService.upsertUser`가 이미 GOOGLE 분기(`sub`/`email`/`name`)를 갖고 있다(단계 8 §5-5). id_token claims가 구글 userinfo와 같은 평면 구조라 **완전히 그대로** 재사용된다. 그래서 upsert 정책 자체를 이 클래스에 다시 쓰지 않았다.
- **principal은 OidcUser 표준 타입 그대로** — 커스텀 principal을 만들지 않으므로 `getName()`은 표준값(sub)이 된다. 이 덕분에 `OAuth2LoginSuccessHandler`가 **한 줄도 바뀌지 않는다**(§4-4).

### 4-3. `SecurityConfig` — userInfoEndpoint 이중 연결

```java
.oauth2Login(oauth -> oauth
    .authorizationEndpoint(endpoint ->
        endpoint.authorizationRequestRepository(cookieAuthorizationRequestRepository))
    // .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
    //   ↑ 단계 9 처리에 의해 제거 — 아래처럼 oidcUserService 연결을 추가하며 확장했다
    // 단계 9: 두 경로를 각각 연결 — 카카오(순수 OAuth2)는 userService,
    // 구글(openid scope → OIDC)은 oidcUserService가 담당한다. 정책은 같은 upsert로 수렴.
    .userInfoEndpoint(userInfo -> userInfo
        .userService(customOAuth2UserService)
        .oidcUserService(customOidcUserService))
    .successHandler(oAuth2LoginSuccessHandler)
    .failureHandler(oAuth2LoginFailureHandler))
```

`.userService(...)`와 `.oidcUserService(...)`는 **동시에 등록 가능**하다 — 어차피 라이브러리가 요청별로 openid 유무를 보고 하나만 호출한다(§3의 분기 그림). 결국 한 요청에는 둘 중 하나만 발동하므로 경쟁·순서 문제가 없다.

### 4-4. 건드리지 않은 것 — 그리고 그것이 왜 가능한가

| 컴포넌트 | 왜 안 바꿨나 |
|----------|--------------|
| `OAuth2LoginSuccessHandler` | `authentication.getName()`이 여전히 sub(=providerId) — OidcUser도 `getName()`이 sub이라 코드 경로 동일 |
| `AuthProvider` enum | GOOGLE 상수 이미 존재 (단계 8 §5-5). "OAuth2 구글"과 "OIDC 구글"은 **같은 제공자** — 프로토콜만 갈아탄 것 |
| DB 스키마 | (provider, providerId) = (GOOGLE, sub) — 값이 완전히 동일하므로 기존 구글 사용자가 **그대로 재로그인**된다 |
| 카카오 관련 코드/설정 | 카카오는 openid를 안 요청하므로 여전히 순수 OAuth2 경로 — 이 단계에서 손대지 않는다 |

**교보재 포인트 — 기존 사용자가 유지되는 것이 왜 강조되는가**: 만약 sub 값이 프로토콜 전환 후 달라졌다면, 단계 8에서 가입한 구글 사용자들이 **모두 새 계정으로 취급**되는 사고가 났을 것이다. OIDC 표준이 sub의 안정성(같은 사용자·같은 client에는 항상 같은 값)을 보장하기 때문에 무손실 전환이 가능하다. 테스트 `should_reuseExistingGoogleUser_whenOidcLogin`(§5)이 이 계약을 코드로 고정한다.

---

## 5. 테스트 전략 — delegate stub과 오염 방지

`CustomOidcUserServiceTest`가 두 케이스로 계약을 고정한다:

| 테스트 | 검증 내용 |
|--------|-----------|
| `should_upsertNewUser_whenFirstOidcLogin` | id_token claims → `upsertUser` 연결. getName()=sub, username=`google_{sub}`, email/nickname 매핑 |
| `should_reuseExistingGoogleUser_whenOidcLogin` | OAuth2 경로로 가입한 구글 사용자를 OIDC 경로가 **같은 계정**으로 재사용 (§4-4의 무손실 전환 보장) |

핵심 트릭은 **delegate 교체**다. 실제 `OidcUserService.loadUser`는 구글의 userinfo/JWK 엔드포인트를 진짜로 호출한다 — 단위 테스트에서 그럴 수 없다. 그래서 `CustomOidcUserService`에 package-private `setDelegate`를 두고 stub으로 갈아끼운다:

```java
@BeforeEach
void stubDelegate() {
  // 표준 OidcUserService라면 id_token 서명 검증 후 DefaultOidcUser를 만든다 — 그 결과만 흉내 낸다
  customOidcUserService.setDelegate(request ->
      new DefaultOidcUser(
          AuthorityUtils.createAuthorityList("OIDC_USER"), request.getIdToken()));
}

@AfterEach
void restoreDelegate() {
  // customOidcUserService는 캐시된 컨텍스트의 싱글톤 — stub을 남기면 다른 테스트가 오염된다
  customOidcUserService.setDelegate(new OidcUserService());
}
```

> **주의 — @AfterEach가 왜 필요한가**: `@SpringBootTest`는 컨텍스트를 **테스트 클래스 간에 캐시**한다(같은 설정이면 재사용). `customOidcUserService`는 그 컨텍스트의 싱글톤 빈이라, 테스트가 끝난 뒤 delegate가 stub인 채로 남으면 **다른 테스트 클래스가 그 stub을 상속받는다**. 그러면 뒤의 테스트가 실제 구글 호출을 기대하는데 stub 결과가 튀어나오거나, 이 클래스만 단독으로 돌릴 때는 통과하지만 전체 실행하면 실패하는 **재현이 어려운 오염**이 생긴다. 원상복구는 stub을 쓰는 쪽의 예의다.

**왜 실제 id_token을 만들지 않나**: delegate가 stub이므로 서명 검증은 안 일어난다. id_token은 값(sub/email/name 등 claim)만 채우면 되고, 서명은 아무 문자열이어도 된다. `OidcIdToken.withTokenValue("test-id-token")` 같이 되는 이유다. **검증은 계약(누가 담당하는가)**의 문제이지 이 테스트의 검증 대상은 아니다.

---

## 6. 남은 것 — 후속 리팩토링 기록

강의 완료 시점에 남은 코드 리뷰 지적사항 두 가지. 다음 단계나 개별 실습으로 정리한다.

### 6-1. `loadUser`의 `@Transactional` 안에서 원격 HTTP 호출

`CustomOidcUserService.loadUser`도, 단계 8의 `CustomOAuth2UserService.loadUser`도 **같은 패턴**의 문제가 있다:

```java
@Override
@Transactional
public OidcUser loadUser(OidcUserRequest userRequest) {
  OidcUser oidcUser = delegate.loadUser(userRequest);   // ← 트랜잭션 안에서 원격 HTTP 호출
  customOAuth2UserService.upsertUser(...);              // ← 실제 DB 작업은 여기부터
  return oidcUser;
}
```

DB 커넥션을 **HTTP 응답 대기 시간 내내 쥐고 있는** 셈이라 커넥션 풀 관점에서 낭비다. 원격 호출을 트랜잭션 밖으로 꺼내는 리팩터가 필요하지만, **두 클래스가 완전히 같은 패턴**이라 개별로 고치면 diff가 두 배가 된다. 두 클래스를 묶어 한 번에 처리하는 것이 낫다는 판단으로 **후속 처리로 미뤘다**.

### 6-2. 카카오 OIDC — 연습 문제 후보

카카오도 OIDC를 지원한다(`openid_connect_id_token` scope). 이 단계에서 배운 그대로:

- yaml 카카오 scope에 `openid` 추가
- 카카오는 `CommonOAuth2Provider`에 없으므로 provider 블록에 `jwk-set-uri`(카카오 JWK 엔드포인트) 추가 필요
- `CustomOidcUserService`는 registrationId 분기에 KAKAO 추가 — 카카오 id_token claims는 구글과 필드명이 다르다(`sub` 대신 카카오 회원번호를 담는 방식 차이 확인 필요)

수강생이 **혼자 해볼 수 있는 크기**의 확장이다 — 이 단계 §3~§5를 다시 읽으며 따라가면 되고, 완료 후 §4-4의 "무손실 전환 보장"이 카카오에도 성립하는지 확인하는 것이 검수 포인트.

---

## 7. 파일 요약

| 파일 | 변경 | 역할 |
|------|------|------|
| `auth/oauth2/CustomOidcUserService` | 신규 | OIDC 경로 사용자 로딩 (delegate 위임 + upsert 재사용) |
| `auth/oauth2/CustomOAuth2UserService` | 무변경 | `upsertUser` 그대로 재사용 (GOOGLE 분기 이미 존재) |
| `auth/oauth2/OAuth2LoginSuccessHandler` | 무변경 | `getName()`=sub 계약 덕에 수정 없음 |
| `global/config/SecurityConfig` | 3줄 수정 | `.userInfoEndpoint`에 `.oidcUserService(...)` 추가 (기존 `.userService(...)`와 병렬) |
| `application.yaml` | 1줄 수정 | 구글 scope에 `openid` 추가 (기존 라인은 주석 흔적으로 보존) |
| `test/.../CustomOidcUserServiceTest` | 신규 | delegate stub으로 id_token claims → upsert 계약 검증 (2 케이스) |

---

## 8. 핵심 요약 한 장

> [!IMPORTANT]
> OIDC는 OAuth2 위에 얹은 **인증 계층** — 토큰 응답에 서명된 id_token(JWT)이 함께 오고,
> Spring Security는 openid scope 유무로 사용자 로딩 서비스를 다르게 부른다.

| 구분 | 내용 |
|------|------|
| 왜 필요한가 | OAuth2는 인가 프로토콜인데 우리는 인증에 유용해 왔다 — OIDC가 그 유용을 표준화 |
| 무엇이 다른가 | access token(불투명, userinfo 조회 필요) vs id_token(JWT, 사용자 정보 내장 + 구글 서명) |
| 검증은 누가 | 라이브러리(`OidcAuthorizationCodeAuthenticationProvider`)가 loadUser 호출 전에 서명·iss·aud·exp·nonce 완료 |
| 우리가 만든 것 | `CustomOidcUserService` 1클래스 — 표준 `OidcUserService`에 위임 후 `CustomOAuth2UserService.upsertUser` 재사용 |
| 전환 비용 | yaml scope 1줄 + 신규 1클래스 + SecurityConfig 1줄 — sub 동일하므로 기존 구글 사용자 무손실 |
| 여전히 남은 것 | loadUser @Transactional 안 원격 호출(두 서비스 공통), 카카오 OIDC 전환(연습 문제) |

---

## 부록. 자주 나오는 질문 (FAQ)

| 질문 | 답변 요약 |
|------|-----------|
| id_token만 있으면 access token은 필요 없나? | 응답에는 여전히 함께 온다. id_token은 "이 사람이 누구다"용, access token은 "구글 API를 계속 호출"용. 우리는 로그인 순간의 사용자 정보만 필요해 access token은 즉시 버린다(카카오 때와 동일). |
| nonce는 뭔가? | 인가 요청 시 우리가 심고 id_token claims에서 되돌려 받는 난수. state가 콜백 위조를 막듯, nonce는 **id_token 위조/재사용**을 막는다. Spring Security가 인가 요청부터 검증까지 전 과정을 처리한다 — 우리 코드에 nonce가 안 보이는 이유. |
| 왜 카카오는 안 바꿨나? | 카카오도 OIDC를 지원하지만 이번 단계는 "두 프로토콜의 대비"가 학습 목표다. 카카오는 순수 OAuth2로 남겨야 §3의 분기가 실제로 갈리는 것을 코드 하나로 관찰할 수 있다. 카카오 OIDC 전환은 §6-2의 연습 문제. |
| OidcUser와 OAuth2User의 관계는? | `OidcUser extends OAuth2User`. 즉 OidcUser는 OAuth2User가 갖는 attributes/getName()에 더해 `getIdToken()`, `getClaims()`, `getUserInfo()`를 추가로 갖는다. 그래서 `getName()`이 여전히 동작하고 SuccessHandler가 수정 없이 재사용된 것. |
| id_token 서명 검증에 실패하면 어디로 가나? | `OAuth2LoginFailureHandler`(단계 8) — 우리 loadUser는 호출되지도 않는다. 실패 사유(예: `invalid_id_token`)는 서버 로그에 남고 클라이언트에는 `OAUTH_LOGIN_FAILED` 401 하나로 응답. |
| yaml에서 provider 블록을 안 쓰는데 구글의 JWK URL은 어디서 오나? | `CommonOAuth2Provider.GOOGLE`이 구글의 discovery(`.well-known/openid-configuration`) 정보를 내장하고 있어 `jwk-set-uri`가 자동 세팅된다. 카카오 OIDC를 하려면(§6-2) 이 값을 provider 블록에 직접 써야 한다. |
| 왜 상속(extends OidcUserService) 대신 위임인가? | Oidc 쪽 서비스는 상속을 가정한 hook을 노출하지 않는다(단계 8 `CustomOAuth2UserService`가 상속한 `DefaultOAuth2UserService`와 대비). 위임이 표준 조언이고, 테스트에서 delegate만 갈아끼우면 되므로 stub 짜기도 쉽다(§5). |
