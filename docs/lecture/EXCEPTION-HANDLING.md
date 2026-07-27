---
type: 보조이론
track: common
tags: [common, theory, exception]
---

# 예외 처리와 HTTP 응답 코드 반환 — 보조 이론 강의

**과정명**: 강의용 Spring Boot 게시판 — 예외 처리 이론 보강
**대상**: Spring Boot 입문 수강생
**관련 코드**: `src/main/java/com/example/board/global/exception/` 패키지 전체
**선수 지식**: Java 예외(try/catch, throw), Spring 컨트롤러/서비스 계층 구조

---

## 학습 목표

이 문서를 끝내면 수강생은:

- 자바 예외와 HTTP 응답 코드가 어떻게 연결되는지 설명할 수 있다
- 이 프로젝트의 예외 계층(`BusinessException` → 하위 4종)을 그려 설명할 수 있다
- `@RestControllerAdvice`가 "한곳에서 모든 예외를 응답으로 바꾸는" 원리를 설명할 수 있다
- `ErrorCode` enum 하나로 메시지와 HTTP 상태를 관리하는 방식의 장점을 말할 수 있다
- 새로운 예외 상황이 생겼을 때 어디를 어떻게 고쳐야 하는지 안다

---

## 1. 왜 예외 처리를 한곳에 모으는가?

### 1-1. 만약 컨트롤러마다 직접 처리한다면

```java
// ❌ 나쁜 예 — 컨트롤러마다 try/catch
@GetMapping("/posts/{id}")
public ResponseEntity<?> getPost(@PathVariable Long id) {
  try {
    Post post = postService.getPost(id);
    return ResponseEntity.ok(post);
  } catch (NoSuchElementException e) {
    return ResponseEntity.status(404).body("없음");   // 응답 형식이 제각각
  } catch (Exception e) {
    return ResponseEntity.status(500).body("오류");    // 모든 메서드에 반복
  }
}
```

이 방식의 문제:

- **중복** — 모든 컨트롤러 메서드마다 같은 try/catch를 반복
- **불일치** — 어떤 곳은 `"없음"`, 어떤 곳은 `{"error":"..."}` — 응답 형식이 통일되지 않음
- **관심사 섞임** — "글을 가져온다"는 본래 로직과 "에러를 어떻게 응답할까"가 한 메서드에 뒤섞임

### 1-2. 이 프로젝트의 방식 — "던지면 한곳에서 받는다"

```
[Service]                    [Spring]                     [GlobalExceptionHandler]
   │                            │                                  │
   │ throw NotFoundException    │                                  │
   ├───────────────────────────▶│ (컨트롤러를 빠져나간 예외를      │
   │                            │  Spring이 가로챔)                 │
   │                            ├──────────────────────────────────▶│
   │                            │                          @ExceptionHandler가
   │                            │                          ErrorCode → HTTP 응답으로 변환
   │                            │◀──────────────────────────────────┤
   │                            │   404 + {"code":"POST_NOT_FOUND",...}
```

**핵심**: 서비스는 그냥 **예외를 던지기만** 한다. 그 예외를 몇 번 코드로, 어떤 JSON으로 응답할지는 `GlobalExceptionHandler` 한곳이 책임진다.

> **확인 질문**
> Q. 컨트롤러마다 try/catch를 쓰지 않고 한곳에 모으면 좋은 점 두 가지는?
> A. (1) 응답 형식이 통일된다 (2) 본래 비즈니스 로직과 에러 처리 관심사가 분리되어 코드가 간결해진다.

---

## 2. 예외 계층 구조

### 2-1. 전체 그림

```
RuntimeException (자바 표준)
   ▲
   │ 상속
BusinessException ─────── ErrorCode 를 필드로 보유
   ▲
   ├── NotFoundException      → 404 NOT_FOUND
   ├── DuplicateException     → 409 CONFLICT
   ├── UnauthorizedException  → 401 UNAUTHORIZED
   └── ForbiddenException     → 403 FORBIDDEN
```

- 모든 도메인 예외는 `BusinessException`을 상속한다.
- `BusinessException`은 `RuntimeException`을 상속한다 → **언체크 예외**라 `throws` 선언이 필요 없고, `@Transactional`이 자동 롤백한다.

### 2-2. 기반 클래스 — BusinessException

`global/exception/BusinessException.java`:

```java
@Getter
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;

  public BusinessException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }
}
```

| 라인 | 의미 |
|------|------|
| `extends RuntimeException` | 언체크 예외. 컴파일러가 `throws`를 강제하지 않음 + 트랜잭션 롤백 대상 |
| `private final ErrorCode errorCode` | **이 예외가 어떤 종류인지를 ErrorCode로 들고 다닌다** — 핸들러가 이걸 보고 응답을 결정 |
| `super(errorCode.getMessage())` | 표준 예외 메시지로 ErrorCode의 메시지를 그대로 사용 |

### 2-3. 하위 예외 — 이름만 다른 얇은 클래스

`global/exception/NotFoundException.java`:

```java
public class NotFoundException extends BusinessException {

  public NotFoundException(ErrorCode errorCode) {
    super(errorCode);
  }
}
```

`DuplicateException`, `UnauthorizedException`, `ForbiddenException`도 **완전히 같은 모양**이다 (이름만 다름).

> **왜 이렇게 얇은 클래스를 따로 만드나?**
> - 코드를 읽을 때 `throw new NotFoundException(...)`이 `throw new BusinessException(...)`보다 **의도가 명확**하다.
> - 나중에 "404만 따로 처리"하고 싶을 때 타입으로 구분할 수 있다.
> - HTTP 상태는 결국 `ErrorCode`가 정하므로, 하위 클래스는 "가독성을 위한 라벨" 역할.

> **확인 질문**
> Q. `BusinessException`이 `Exception`이 아니라 `RuntimeException`을 상속하는 이유 두 가지는?
> A. (1) 언체크 예외라 `throws` 선언 없이 던질 수 있다 (2) Spring `@Transactional`이 RuntimeException에 대해 기본적으로 롤백한다.

---

## 3. ErrorCode — 메시지와 HTTP 상태를 한곳에서 관리

### 3-1. enum 하나가 곧 "에러 카탈로그"

`global/exception/ErrorCode.java`:

```java
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
  POST_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."),

  DUPLICATE_USERNAME(HttpStatus.CONFLICT, "이미 사용 중인 username입니다."),
  NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 nickname입니다."),

  POST_ACCESS_DENIED(HttpStatus.FORBIDDEN, "게시글에 대한 권한이 없습니다."),
  ADMIN_ONLY(HttpStatus.FORBIDDEN, "관리자만 수행할 수 있습니다."),

  LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
  LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "username 또는 password가 올바르지 않습니다."),
  INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

  private final HttpStatus status;
  private final String message;
}
```

**각 enum 상수가 3가지를 한 줄에 묶는다:**

| 구성 요소 | 예 | 역할 |
|-----------|-----|------|
| 이름(name) | `POST_NOT_FOUND` | 응답 JSON의 `code` 필드가 된다 |
| status | `HttpStatus.NOT_FOUND` (404) | 반환할 HTTP 상태 코드 |
| message | "게시글을 찾을 수 없습니다." | 응답 JSON의 `message` 필드 |

### 3-2. 이 프로젝트의 ErrorCode → HTTP 상태 매핑표

| HTTP 상태 | ErrorCode | 언제 |
|-----------|-----------|------|
| **400** Bad Request | `INVALID_INPUT` | `@Valid` 검증 실패 |
| **401** Unauthorized | `LOGIN_REQUIRED`, `LOGIN_FAILED` | 비로그인 / 로그인 실패 |
| **403** Forbidden | `POST_ACCESS_DENIED`, `ADMIN_ONLY` | 권한 없음 |
| **404** Not Found | `USER/PROFILE/BOARD/POST_NOT_FOUND` | 리소스 없음 |
| **409** Conflict | `DUPLICATE_USERNAME/EMAIL/BOARD_NAME`, `NICKNAME_DUPLICATED` | 중복 |
| **500** Internal Server Error | `INTERNAL_ERROR` | 예상치 못한 예외 |

> **포인트 — enum으로 모으면 좋은 점**
> - 새 에러를 추가할 때 **한 줄만** 추가하면 code/status/message가 한 번에 정해진다.
> - "이 프로젝트가 반환할 수 있는 모든 에러"를 한 파일에서 카탈로그처럼 본다.
> - 메시지 오타·상태 코드 실수를 한곳에서 관리.

### 3-3. 잠깐 — "필드를 가진 enum"이라는 개념

`Role`처럼 값만 나열하는 enum과 `ErrorCode`는 생김새가 다르다. 비교해 보자.

```java
// (A) 단순 enum — 값만 나열 (예: Role)
public enum Role {
  USER, ADMIN
}

// (B) 데이터를 가진 enum — 각 상수가 값을 들고 있음 (예: ErrorCode)
public enum ErrorCode {
  POST_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다.");
  //             └─────────── 생성자에 넘기는 값 ───────────┘

  private final HttpStatus status;   // 각 상수가 보관하는 데이터
  private final String message;
}
```

자바 enum의 상수(`USER`, `POST_NOT_FOUND`...)는 사실 **그 enum 타입의 객체**다. (B)처럼 enum에 필드와 생성자를 두면, 각 상수가 **자기만의 데이터를 들고 있는 객체**가 된다.

```
ErrorCode.POST_NOT_FOUND  ─▶  { status: 404,  message: "게시글을 찾을 수 없습니다." }
ErrorCode.ADMIN_ONLY      ─▶  { status: 403,  message: "관리자만 수행할 수 있습니다." }
ErrorCode.LOGIN_REQUIRED  ─▶  { status: 401,  message: "로그인이 필요합니다." }
```

즉 `POST_NOT_FOUND`는 단순한 이름표가 아니라 **"이름 + 상태코드 + 메시지"를 한 덩어리로 묶은 상수 객체**다.

### 3-4. 값은 어떻게 들어가나 — 생성자와 Lombok

각 상수 옆의 괄호 `(HttpStatus.NOT_FOUND, "...")`는 **enum 생성자 호출**이다. 클래스에서 `new`로 객체를 만들 때 생성자에 값을 넘기는 것과 똑같은 원리다.

```java
@Getter                  // (1) status/message의 getter를 자동 생성
@RequiredArgsConstructor // (2) final 필드를 받는 생성자를 자동 생성
public enum ErrorCode {

  POST_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."),
  // ...

  private final HttpStatus status;
  private final String message;
}
```

| Lombok 어노테이션 | 자동으로 만들어주는 것 | 효과 |
|-------------------|------------------------|------|
| `@RequiredArgsConstructor` | `ErrorCode(HttpStatus status, String message)` 생성자 | 각 상수가 괄호로 status·message를 넘길 수 있게 됨 |
| `@Getter` | `getStatus()`, `getMessage()` 메서드 | 보관한 값을 밖에서 꺼내 쓸 수 있게 됨 |

> Lombok 없이 손으로 쓰면 아래와 같다. **Lombok은 이 반복 코드를 대신 써줄 뿐**이며, 동작 원리는 동일하다.
>
> ```java
> private final HttpStatus status;
> private final String message;
>
> ErrorCode(HttpStatus status, String message) {  // @RequiredArgsConstructor가 대신
>   this.status = status;
>   this.message = message;
> }
> public HttpStatus getStatus() { return status; } // @Getter가 대신
> public String getMessage()    { return message; }
> ```

> `final` + 생성자에서만 값 설정 → enum 상수의 status·message는 **한 번 정해지면 절대 바뀌지 않는다(불변)**. 그래서 앱 전체가 같은 `ErrorCode.POST_NOT_FOUND` 하나를 공유해도 안전하다.

### 3-5. 코드 안에서 꺼내 쓰는 3가지 — name / getStatus / getMessage

`ErrorCode` 상수 하나에서 **세 가지 정보**를 꺼내 응답을 조립한다.

| 꺼내는 법 | 결과 예 | 어디에 쓰이나 |
|-----------|---------|---------------|
| `.name()` | `"POST_NOT_FOUND"` | 응답 JSON의 `code` 필드 (모든 enum이 기본 제공하는 메서드) |
| `.getStatus()` | `HttpStatus.NOT_FOUND` (404) | `ResponseEntity.status(...)`의 HTTP 상태 |
| `.getMessage()` | `"게시글을 찾을 수 없습니다."` | 응답 JSON의 `message` 필드 |

실제로 이 셋이 어떻게 쓰이는지 보자.

**① 예외에 담을 때** (`BusinessException`):

```java
public BusinessException(ErrorCode errorCode) {
  super(errorCode.getMessage());   // ← getMessage(): 표준 예외 메시지로 사용
  this.errorCode = errorCode;      // ← ErrorCode 통째로 보관
}
```

**② 응답으로 변환할 때** (`GlobalExceptionHandler`):

```java
ErrorCode errorCode = e.getErrorCode();
return ResponseEntity
    .status(errorCode.getStatus())            // ← getStatus(): 404
    .body(ErrorResponse.of(errorCode));       // ← 아래 ③으로 연결
```

**③ JSON 본문을 만들 때** (`ErrorResponse`):

```java
public static ErrorResponse of(ErrorCode errorCode) {
  return new ErrorResponse(
      errorCode.name(),         // ← name(): "POST_NOT_FOUND" → code 필드
      errorCode.getMessage(),   // ← getMessage(): message 필드
      LocalDateTime.now(),
      null);
}
```

정리하면, `throw new NotFoundException(ErrorCode.POST_NOT_FOUND)` 한 줄을 던지면 그 안의 `ErrorCode` 하나가 **흘러가며 code·status·message를 모두 공급**한다.

```
ErrorCode.POST_NOT_FOUND
   │  .name()       → "POST_NOT_FOUND"  ─┐
   │  .getStatus()  → 404               ─┼─▶  404 응답 + {"code":"POST_NOT_FOUND",
   │  .getMessage() → "게시글을..."      ─┘                "message":"게시글을...", ...}
```

### 3-6. 새 ErrorCode를 추가하는 법 (실습)

"닉네임이 비속어면 거절"이라는 새 규칙을 만든다고 하자. **ErrorCode에 한 줄만 추가**하면 된다.

```java
public enum ErrorCode {
  // ... 기존 상수들 ...
  NICKNAME_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "사용할 수 없는 닉네임입니다.");  // ← 추가
  // ...
}
```

그리고 서비스에서 던지기만 하면 끝이다. 어떤 예외 클래스로 던질지는 **원하는 HTTP 의미**에 맞춰 고른다(여기선 400이므로 `BusinessException` 또는 새 의미의 예외).

```java
if (isBadWord(nickname)) {
  throw new BusinessException(ErrorCode.NICKNAME_NOT_ALLOWED);
}
```

`name()`이 자동으로 `"NICKNAME_NOT_ALLOWED"` 문자열을 만들어 주므로 code 값을 따로 적을 필요도 없다.

> **확인 질문**
> Q. `ErrorCode.POST_NOT_FOUND`에서 `code`(문자열), HTTP 상태, 메시지는 각각 어떤 메서드로 꺼내는가?
> A. `code`는 모든 enum 기본 메서드인 `name()` → `"POST_NOT_FOUND"`, HTTP 상태는 `getStatus()` → 404, 메시지는 `getMessage()`. (뒤의 두 개는 `@Getter`가 만든 메서드.)

> **확인 질문**
> Q. `Role` enum과 `ErrorCode` enum의 가장 큰 차이는?
> A. `Role`은 값만 나열하는 단순 enum이고, `ErrorCode`는 각 상수가 생성자를 통해 status·message **데이터를 함께 들고 있는** enum이다.

---

## 4. 던지는 쪽 — 서비스에서 예외 발생

서비스 계층에서 문제 상황을 만나면 알맞은 예외를 던진다.

```java
// PostService — 글이 없으면 404
private Post findPost(Long id) {
  return postRepository.findDetailById(id)
      .orElseThrow(() -> new NotFoundException(ErrorCode.POST_NOT_FOUND));
}

// PostService — 작성자가 아니면 403
private void validateAuthor(Post post, Long userId) {
  if (!post.isAuthor(userId)) {
    throw new ForbiddenException(ErrorCode.POST_ACCESS_DENIED);
  }
}

// BoardService — ADMIN이 아니면 403
private void validateAdmin(Long userId) {
  User user = userRepository.findById(userId)
      .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
  if (user.getRole() != Role.ADMIN) {
    throw new ForbiddenException(ErrorCode.ADMIN_ONLY);
  }
}

// PostController — 비로그인이면 401
private Long requireLogin(Long loginUserId) {
  if (loginUserId == null) {
    throw new UnauthorizedException(ErrorCode.LOGIN_REQUIRED);
  }
  return loginUserId;
}
```

**패턴은 항상 동일**: `throw new XxxException(ErrorCode.YYY)`. 던지고 나면 끝 — 응답 변환은 신경 쓰지 않는다.

---

## 5. 받는 쪽 — GlobalExceptionHandler

### 5-1. @RestControllerAdvice가 모든 예외를 가로챈다

`global/exception/GlobalExceptionHandler.java`:

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  // (1) 우리가 던진 모든 도메인 예외
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
    ErrorCode errorCode = e.getErrorCode();
    log.warn("BusinessException: code={}, message={}", errorCode.name(), e.getMessage());
    return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
  }

  // (2) @Valid 검증 실패 — 필드별 오류 목록
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
    List<ErrorResponse.FieldErrorDetail> errors = e.getBindingResult().getFieldErrors().stream()
        .map(error -> new ErrorResponse.FieldErrorDetail(error.getField(), error.getDefaultMessage()))
        .toList();
    log.warn("Validation failed: {}", errors);
    return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
        .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, errors));
  }

  // (3) 예상하지 못한 모든 예외 — 상세를 숨기고 500
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception e) {
    log.error("Unexpected exception", e);
    return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
        .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
  }
}
```

### 5-2. 핸들러 3개의 역할

| 핸들러 | 잡는 예외 | 응답 |
|--------|-----------|------|
| `handleBusinessException` | `BusinessException` **및 모든 하위**(NotFound/Duplicate/Unauthorized/Forbidden) | ErrorCode의 status + code + message |
| `handleValidationException` | `MethodArgumentNotValidException` (`@Valid` 실패 시 Spring이 던짐) | 400 + 필드별 오류 배열 |
| `handleException` | 위에서 안 잡힌 **나머지 전부** | 500 + 일반 메시지 (내부 정보 숨김) |

> **핵심 1 — 하나의 핸들러가 4종 예외를 모두 잡는 이유**
> `@ExceptionHandler(BusinessException.class)`는 **부모 타입**을 지정했으므로 `NotFoundException`, `ForbiddenException` 등 모든 자식이 여기로 들어온다. 그리고 응답 상태는 `e.getErrorCode().getStatus()`에서 가져오므로, 각 예외가 알아서 자기 HTTP 코드를 결정한다. → **핸들러를 예외마다 만들 필요가 없다.**

> **핵심 2 — 가장 구체적인 핸들러가 우선**
> `MethodArgumentNotValidException`은 `Exception`의 자식이지만, Spring은 **가장 구체적인 타입의 핸들러를 먼저** 선택한다. 그래서 검증 실패는 (3)이 아니라 (2)로 간다.

> **핵심 3 — 500은 상세를 숨긴다**
> `handleException`은 `log.error`로 스택트레이스를 **서버 로그에만** 남기고, 응답에는 "서버 내부 오류" 일반 메시지만 보낸다. DB 구조, 파일 경로 같은 내부 정보가 공격자에게 노출되는 것을 막는다.

---

## 6. 응답 형식 — ErrorResponse

`global/exception/ErrorResponse.java`:

```java
@JsonInclude(JsonInclude.Include.NON_NULL)   // null 필드는 JSON에서 제외
public record ErrorResponse(
    String code,
    String message,
    LocalDateTime timestamp,
    List<FieldErrorDetail> errors
) {

  public record FieldErrorDetail(String field, String reason) {
  }

  public static ErrorResponse of(ErrorCode errorCode) {
    return new ErrorResponse(errorCode.name(), errorCode.getMessage(), LocalDateTime.now(), null);
  }

  public static ErrorResponse of(ErrorCode errorCode, List<FieldErrorDetail> errors) {
    return new ErrorResponse(errorCode.name(), errorCode.getMessage(), LocalDateTime.now(), errors);
  }
}
```

### 6-1. 두 가지 응답 모양

**일반 예외 (errors 없음)** — `@JsonInclude(NON_NULL)` 덕분에 `errors` 필드가 아예 빠진다:

```json
{
  "code": "POST_NOT_FOUND",
  "message": "게시글을 찾을 수 없습니다.",
  "timestamp": "2026-06-14T10:00:00"
}
```

**검증 실패 (errors 배열 포함)**:

```json
{
  "code": "INVALID_INPUT",
  "message": "입력값이 올바르지 않습니다.",
  "timestamp": "2026-06-14T10:00:00",
  "errors": [
    {"field": "username", "reason": "크기가 4에서 50 사이여야 합니다"},
    {"field": "email", "reason": "올바른 형식의 이메일 주소여야 합니다"}
  ]
}
```

> `@JsonInclude(JsonInclude.Include.NON_NULL)` — 값이 `null`인 필드는 JSON 직렬화에서 제외한다. 그래서 일반 예외에서는 `errors`가 보이지 않고, 검증 실패에서만 나타난다.

---

## 7. 전체 흐름 — 한 장으로 보기

```
요청: GET /api/v1/posts/999999  (없는 글)

  PostController.getPost(999999)
        │
        ▼
  PostService.findPost(999999)
        │  postRepository.findDetailById(999999) → Optional.empty()
        ▼
  throw new NotFoundException(ErrorCode.POST_NOT_FOUND)   ← ① 던진다
        │
        │  (컨트롤러를 빠져나감 — Spring이 가로챔)
        ▼
  GlobalExceptionHandler.handleBusinessException(e)        ← ② 받는다
        │  errorCode = POST_NOT_FOUND
        │  status = 404, code = "POST_NOT_FOUND"
        ▼
  ResponseEntity.status(404).body(ErrorResponse.of(...))   ← ③ 변환
        │
        ▼
  HTTP/1.1 404 Not Found
  {"code":"POST_NOT_FOUND","message":"게시글을 찾을 수 없습니다.","timestamp":"..."}
```

---

## 8. 실전 — 새 예외 상황을 추가하려면?

예: "이미 추천한 글을 또 추천하면 409 에러"를 추가한다고 하자.

**단 두 곳만 고치면 된다:**

```java
// 1단계: ErrorCode에 한 줄 추가
ALREADY_RECOMMENDED(HttpStatus.CONFLICT, "이미 추천한 게시글입니다."),

// 2단계: 서비스에서 던지기
if (recommendRepository.existsByPostIdAndUserId(postId, userId)) {
  throw new DuplicateException(ErrorCode.ALREADY_RECOMMENDED);
}
```

`GlobalExceptionHandler`는 **고칠 필요 없다.** `DuplicateException`은 이미 `handleBusinessException`이 잡고, 상태 코드는 `ErrorCode`가 정하기 때문이다. 이것이 이 구조의 가장 큰 장점이다.

> **확인 질문**
> Q. 새 비즈니스 예외를 추가할 때 `GlobalExceptionHandler`를 수정하지 않아도 되는 이유는?
> A. 핸들러가 부모 타입 `BusinessException`을 잡고, HTTP 상태는 예외가 들고 있는 `ErrorCode`에서 가져오기 때문이다. 새 `ErrorCode` 한 줄 + 서비스에서 `throw`만 하면 된다.

---

## 9. 핵심 요약 한 장

```
┌────────────────────────────────────────────────────────────────────┐
│ 던진다  (Service/Controller)                                        │
│   throw new NotFoundException(ErrorCode.POST_NOT_FOUND)             │
│                                                                     │
│ 예외 계층:  RuntimeException                                        │
│              └ BusinessException (ErrorCode 보유)                   │
│                  ├ NotFoundException     (404)                      │
│                  ├ DuplicateException    (409)                      │
│                  ├ UnauthorizedException (401)                      │
│                  └ ForbiddenException    (403)                      │
│                                                                     │
│ 카탈로그:  ErrorCode enum = code + HTTP status + message            │
│                                                                     │
│ 받는다  (@RestControllerAdvice GlobalExceptionHandler)              │
│   ① BusinessException     → ErrorCode의 status로 응답               │
│   ② @Valid 실패           → 400 + 필드별 errors 배열                │
│   ③ 그 외 모든 예외       → 500 (상세 숨김, 로그만)                 │
│                                                                     │
│ 응답:  ErrorResponse(code, message, timestamp, errors?)            │
└────────────────────────────────────────────────────────────────────┘
```

---

## 부록. 자주 나오는 질문 (FAQ)

| 질문 | 답변 요약 |
|------|-----------|
| 왜 예외마다 `@ExceptionHandler`를 안 만드나? | 부모 타입(`BusinessException`) 하나만 잡으면 모든 자식이 들어오고, 상태는 `ErrorCode`가 정하므로 불필요. |
| 404와 403을 다르게 응답하고 싶으면? | `ErrorCode`에서 status만 다르게 정의하면 됨. 핸들러는 그대로. |
| 검증 실패가 왜 (3)이 아니라 (2)로 가나? | Spring은 가장 구체적인 타입의 핸들러를 먼저 선택. `MethodArgumentNotValidException`이 더 구체적. |
| 500 응답에 왜 상세 메시지를 안 넣나? | DB 구조·내부 경로 노출 방지(보안). 상세는 `log.error`로 서버 로그에만 남긴다. |
| `@Transactional`과 예외의 관계는? | `RuntimeException`(언체크) 발생 시 트랜잭션이 자동 롤백. `BusinessException`이 RuntimeException이라 롤백된다. |
| 응답에서 `errors`가 안 보일 때가 있는데? | `@JsonInclude(NON_NULL)` 때문. errors가 null인 일반 예외에서는 필드가 통째로 빠진다. |
| `code` 필드 문자열은 어디서 오나? | enum 기본 메서드 `name()`이 상수 이름을 그대로 문자열로 돌려준다. `POST_NOT_FOUND` → `"POST_NOT_FOUND"`. 따로 적지 않아도 된다. |
| enum에 어떻게 status/message가 붙나? | `@RequiredArgsConstructor`가 만든 생성자에 각 상수가 `(HttpStatus, "메시지")`를 넘기고, `@Getter`가 만든 `getStatus()`/`getMessage()`로 꺼낸다. |
| `Role`처럼 값만 있는 enum과 뭐가 다른가? | `ErrorCode`는 각 상수가 **데이터(status·message)를 보관하는** enum이다. 필드 + 생성자 + getter가 추가된 형태. |
| HTTP 상태만 바꾸고 싶으면? | `ErrorCode` 정의에서 그 상수의 `HttpStatus`만 교체하면 된다. 던지는 코드·핸들러는 그대로. |
