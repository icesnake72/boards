---
step: 6
track: auth
tags: [auth, authorization]
requires: ["[[SPRING-SECURITY-STANDARD]]", "[[SECURITY-FILTER-CHAIN]]"]
status: 완료
---

# 메서드 보안 — @PreAuthorize와 자원 소유권 인가 (단계 6)

**과정명**: 강의용 Spring Boot 게시판 — 단계 6 (메서드 보안)
**대상**: 단계 5(refresh token httpOnly 쿠키)를 마친 수강생
**브랜치**: `step6-method-security`
**관련 코드**: `post/PostSecurity.java`, `post/PostController.java`, `board/BoardController.java`, `global/config/SecurityConfig.java`
**선수 지식**: [SPRING-SECURITY-STANDARD.md](SPRING-SECURITY-STANDARD.md)(URL 인가), [SECURITY-FILTER-CHAIN.md](SECURITY-FILTER-CHAIN.md)

---

## 학습 목표

이 문서를 끝내면 수강생은:

- URL 레벨 인가(`authorizeHttpRequests`)와 메서드 레벨 인가(`@PreAuthorize`)의 차이를 안다
- URL 규칙으로 표현할 수 없는 **자원 소유권**("작성자만")을 커스텀 빈으로 구현할 수 있다
- `@EnableMethodSecurity`와 SpEL 표현식(`@bean.method(#arg)`)의 동작을 이해한다
- 메서드 보안 거부가 어디서 잡혀 어떤 응답이 되는지 설명할 수 있다

---

## 1. 왜 메서드 보안인가 — URL 규칙의 한계

단계 3~5의 인가는 두 곳에 있었습니다:

- **URL 레벨**(`SecurityConfig`): `requestMatchers(POST, "/boards").hasRole("ADMIN")` — 경로·메서드·역할로 판단
- **서비스 수동 검사**(`PostService`): `if (!post.isAuthor(userId)) throw ...` — 작성자 비교

문제: **"이 글의 작성자만 수정 가능"** 같은 규칙은 URL만 봐선 판단할 수 없습니다. `PUT /posts/5`라는 요청만으로는 "5번 글의 작성자가 너냐?"를 알 수 없죠 — **DB의 데이터(글의 작성자)를 봐야** 합니다. 그래서 단계 5까지는 이걸 서비스 코드에 수동으로 두었습니다.

단계 6은 이 인가를 **메서드 레벨 어노테이션**으로 옮깁니다:

```
URL 레벨 (경로/역할)        →  @PreAuthorize("hasRole('ADMIN')")   메서드에
서비스 수동 (자원 소유권)   →  @PreAuthorize("@postSecurity.isAuthor(#id, ...)")
```

> **핵심**: 메서드 보안은 (1) 역할 검사를 **메서드에 선언적으로** 붙일 수 있고, (2) **커스텀 로직(소유권)** 을 SpEL로 호출할 수 있습니다. 특히 (2)가 URL 규칙이 못 하는 일입니다.

---

## 2. 활성화 — @EnableMethodSecurity

`SecurityConfig`:

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity      // ← 단계 6 추가 (prePostEnabled 기본 true → @PreAuthorize 사용 가능)
public class SecurityConfig { ... }
```

이 한 줄로 `@PreAuthorize`/`@PostAuthorize`가 동작합니다. 동작 원리는 **AOP 프록시** — 어노테이션이 붙은 빈 메서드 호출을 가로채, 메서드 실행 **전에** 표현식을 평가합니다.

---

## 3. 역할 기반 — Board를 메서드로 이동

**Before (단계 5, URL 레벨):**
```java
// SecurityConfig
.requestMatchers(HttpMethod.POST, "/api/v1/boards").hasRole("ADMIN")
.requestMatchers(HttpMethod.PUT, "/api/v1/boards/*").hasRole("ADMIN")
.requestMatchers(HttpMethod.DELETE, "/api/v1/boards/*").hasRole("ADMIN")
```

**After (단계 6, 메서드 레벨):**
```java
// BoardController
@PreAuthorize("hasRole('ADMIN')")
@PostMapping
public BoardResponse create(...) { ... }

@PreAuthorize("hasRole('ADMIN')")
@PutMapping("/{id}")
public BoardResponse update(...) { ... }

@PreAuthorize("hasRole('ADMIN')")
@DeleteMapping("/{id}")
public void delete(...) { ... }
```

`SecurityConfig`에서는 board hasRole 3줄을 **제거**하고, 공개 GET과 `anyRequest().authenticated()`만 남깁니다:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/boards/**").permitAll()   // 조회는 공개
// (board 쓰기 hasRole 규칙은 BoardController @PreAuthorize로 이동)
.anyRequest().authenticated()
```

> **동작은 동일**: 비로그인 → 401(인증 필터/`authenticated()`), 로그인 USER → 403(`@PreAuthorize` 거부), ADMIN → 201. 바뀐 건 "인가 규칙이 어디에 적히는가"뿐입니다.

---

## 4. 자원 소유권 — 커스텀 보안 빈 (핵심)

URL 규칙으로 못 하는 "작성자만"을 커스텀 빈으로 구현합니다.

**`post/PostSecurity.java`:**
```java
@Component("postSecurity")     // SpEL에서 @postSecurity 로 참조할 이름
@RequiredArgsConstructor
public class PostSecurity {

  private final PostRepository postRepository;

  public boolean isAuthor(Long postId, CustomUserDetails user) {
    Long authorId = postRepository.findAuthorIdById(postId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.POST_NOT_FOUND));  // 없으면 404
    return authorId.equals(user.getId());   // 작성자면 true, 아니면 false → 403
  }
}
```

**`PostController`:**
```java
@PreAuthorize("@postSecurity.isAuthor(#id, authentication.principal)")
@PutMapping("/posts/{id}")
public PostResponse update(@PathVariable Long id, @Valid @RequestBody PostUpdateRequest request) {
  return postService.update(id, request);   // 권한 검사가 빠져 시그니처가 단순해짐
}

@PreAuthorize("@postSecurity.isAuthor(#id, authentication.principal)")
@DeleteMapping("/posts/{id}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void delete(@PathVariable Long id) {
  postService.delete(id);
}
```

SpEL 표현식 해석:

| 조각 | 의미 |
|------|------|
| `@postSecurity` | `@Component("postSecurity")` 빈을 참조 |
| `.isAuthor(...)` | 그 빈의 메서드 호출 — true면 통과, false면 `AccessDeniedException` |
| `#id` | 컨트롤러 메서드의 `id` 파라미터 값 (경로 변수) |
| `authentication.principal` | SecurityContext의 principal = 우리 `CustomUserDetails` |

> **`PostService`에서 `validateAuthor` 제거**: 권한 검사가 메서드 보안으로 올라갔으므로 서비스는 순수 비즈니스 로직만 남고 `update(id, request)`처럼 시그니처가 단순해집니다.

> **`#id` 바인딩 주의**: SpEL이 `#id`로 파라미터를 찾으려면 컴파일 시 파라미터 이름이 유지돼야 합니다. `build.gradle`에 `-parameters` 컴파일 옵션을 명시했습니다.

---

## 5. 거부는 어디서 잡히나 — 중요한 동작 변경

단계 6에서 **`@PreAuthorize` 거부의 처리 위치가 단계 3의 URL 인가와 다릅니다.**

```
URL 인가 거부 (authorizeHttpRequests)
   → AuthorizationFilter(필터 단계)에서 거부
   → RestAccessDeniedHandler (필터의 exceptionHandling)

@PreAuthorize 거부 (메서드 보안)
   → 컨트롤러 진입 시점에 AuthorizationDeniedException(AccessDeniedException 하위) 발생
   → 필터를 이미 통과한 뒤라 RestAccessDeniedHandler가 아니라
   → @RestControllerAdvice(GlobalExceptionHandler)가 잡아야 한다
```

그래서 `GlobalExceptionHandler`에 핸들러를 **추가**했습니다 (없으면 500이 됨):

```java
@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
  return ResponseEntity.status(ErrorCode.ACCESS_DENIED.getStatus())
      .body(ErrorResponse.of(ErrorCode.ACCESS_DENIED));   // 403
}
```

> **함정**: Spring Security 6.x에서 `@PreAuthorize` 거부는 메서드 호출 지점(AOP)에서 던져져 **MVC 예외 흐름**을 탑니다. 필터 단계의 `AccessDeniedHandler`로는 안 잡히므로, `@RestControllerAdvice`에 `AccessDeniedException` 핸들러가 반드시 필요합니다. 이걸 빠뜨리면 403이어야 할 게 500으로 나갑니다.

### 동작 변경 정리

| 상황 | 단계 5 | 단계 6 |
|------|--------|--------|
| 타인이 글 수정/삭제 | 403 `POST_ACCESS_DENIED` (서비스 `ForbiddenException`) | **403 `ACCESS_DENIED`** (메서드 보안 → `AccessDeniedException`) |
| 없는 글 수정/삭제 | 404 `POST_NOT_FOUND` | **404 `POST_NOT_FOUND` 유지** (`PostSecurity`가 NotFound를 던짐) |
| USER가 board 생성 | 403 `ACCESS_DENIED` | 403 `ACCESS_DENIED` (동일) |

> **`POST_ACCESS_DENIED` → `ACCESS_DENIED` 통합**: 메서드 보안 거부는 모두 `AccessDeniedException`으로 일원화되어 `ACCESS_DENIED`로 응답됩니다. 자원별 메시지를 잃는 대신, 인가 거부 응답이 한 형태로 통일됩니다. (`POST_ACCESS_DENIED`는 미사용 상태로 보존 — 주석 표시)

> **404 보존 트릭**: `@PreAuthorize`는 메서드 실행 전에 평가되므로, 없는 글이면 본문(`findPost`로 404 던지던)에 도달하기 전에 막힙니다. 그래서 `PostSecurity.isAuthor`가 글을 조회해 **없으면 `NotFoundException`을 던져** 404를 보존합니다(인가보다 "존재 여부"를 먼저 확정).

---

## 6. 전체 인가 지도 (단계 6 기준)

```
요청
  │
  ▼
[ Security Filter Chain ]
  JwtAuthenticationFilter        토큰 → SecurityContext
  AuthorizationFilter            URL 규칙: 공개 GET / 나머지 authenticated
     │ 비로그인 → 401 (EntryPoint)
     ▼ (인증된 요청 통과)
[ 컨트롤러 진입 시 @PreAuthorize 평가 ]   ← 단계 6 추가 지점
     ├ Board: hasRole('ADMIN')           USER → 403 (ACCESS_DENIED)
     └ Post : @postSecurity.isAuthor()   타인 → 403 / 없는 글 → 404
     ▼ (인가 통과)
[ Controller → Service ]                 순수 비즈니스 로직
```

| 인가 종류 | 어디에 | 표현 |
|-----------|--------|------|
| 인증 필요 여부(coarse) | `SecurityConfig` URL 규칙 | permitAll / authenticated |
| 역할(role) | `@PreAuthorize("hasRole('ADMIN')")` | 메서드 |
| 자원 소유권 | `@PreAuthorize("@postSecurity.isAuthor(...)")` | 메서드 + 커스텀 빈 |

---

## 7. 수정된 파일 요약

| 파일 | 변경 |
|------|------|
| `SecurityConfig` | `@EnableMethodSecurity` 추가, board hasRole URL 규칙 3줄 제거 |
| `BoardController` | create/update/delete에 `@PreAuthorize("hasRole('ADMIN')")` |
| `PostSecurity` (신규) | `isAuthor(postId, user)` — 없으면 404, 작성자 비교 |
| `PostController` | update/delete에 `@PreAuthorize("@postSecurity.isAuthor(...)")` |
| `PostService` | `validateAuthor` 제거, 시그니처 단순화 |
| `PostRepository` | `findAuthorIdById` 추가(작성자 id만 경량 조회) |
| `GlobalExceptionHandler` | `AccessDeniedException` 핸들러 추가(403 일원화) |
| `ErrorCode` | `POST_ACCESS_DENIED`에 "ACCESS_DENIED로 통합" 주석 |
| `build.gradle` | `-parameters`(SpEL `#id` 바인딩) |

---

## 8. 핵심 요약 한 장

```
┌────────────────────────────────────────────────────────────────────┐
│ 활성화:  @EnableMethodSecurity                                       │
│                                                                     │
│ 역할:    @PreAuthorize("hasRole('ADMIN')")        ← BoardController  │
│ 소유권:  @PreAuthorize("@postSecurity.isAuthor(#id, principal)")     │
│           - URL 규칙으로 못 하는 '작성자만'을 커스텀 빈으로          │
│           - 없으면 NotFound(404), 작성자 아니면 false(403)           │
│                                                                     │
│ 거부 처리:  @PreAuthorize 거부 = AccessDeniedException               │
│             → 필터가 아니라 @RestControllerAdvice가 잡음             │
│             → GlobalExceptionHandler에 핸들러 필수 (없으면 500)      │
│                                                                     │
│ 변경:  타인 글 수정 403 코드 POST_ACCESS_DENIED → ACCESS_DENIED      │
│        404(없는 글)는 PostSecurity가 NotFound 던져 보존              │
└────────────────────────────────────────────────────────────────────┘
```

---

## 부록. 자주 나오는 질문 (FAQ)

| 질문 | 답변 요약 |
|------|-----------|
| URL 인가로 다 하면 안 되나? | "작성자만"처럼 DB 데이터를 봐야 하는 규칙은 URL만으로 표현 불가 → 메서드 보안 필요. |
| `@postSecurity`는 어떻게 참조되나? | `@Component("postSecurity")`의 이름. SpEL `@beanName.method()`로 호출. |
| `#id`가 null이면? | 파라미터 이름이 컴파일에 안 남으면 바인딩 실패. `-parameters` 옵션으로 보장. |
| 왜 GlobalExceptionHandler에 AccessDeniedException을? | `@PreAuthorize` 거부는 컨트롤러 진입(AOP) 시 발생 → MVC 예외 흐름 → 필터 핸들러로 안 잡힘. 없으면 500. |
| 404가 왜 유지되나? | `PostSecurity.isAuthor`가 글을 조회해 없으면 `NotFoundException`을 던지기 때문. 인가보다 존재 확인을 먼저. |
| ADMIN이 남의 글도 수정하게 하려면? | `@PreAuthorize("hasRole('ADMIN') or @postSecurity.isAuthor(#id, authentication.principal)")`. |
| 메서드 보안은 컨트롤러에만? | 아니다. 서비스 등 어떤 Spring 빈 메서드에도 가능(AOP 프록시 기반). 여기선 가독성을 위해 컨트롤러에 둠. |
