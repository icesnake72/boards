# 테스트 코드 작성과 실행 — 보조 실습 강의

**과정명**: 강의용 Spring Boot 게시판 — 테스트 작성/실행 가이드
**대상**: Spring Boot 입문 수강생
**관련 코드**: `src/test/java/com/example/board/` 전체
**선수 지식**: 본 프로젝트의 서비스/예외 구조 이해, 터미널 사용

---

## 학습 목표

이 문서를 끝내면 수강생은:

- 테스트를 왜 작성하는지, 무엇을 테스트하는지 설명할 수 있다
- 테스트 코드의 기본 골격(Given-When-Then, AAA)을 따라 작성할 수 있다
- 이 프로젝트의 실제 테스트 3종(서비스, 예외, MockMvc)을 읽고 흉내 낼 수 있다
- 테스트를 **하나씩** 실행하는 방법과 **일괄** 실행하는 방법을 구분해 쓸 수 있다
- 테스트 결과 리포트를 어디서 확인하는지 안다

---

## 1. 테스트를 왜, 무엇을 작성하나?

### 1-1. 왜 작성하나

- **회귀 방지** — 코드를 고쳤을 때 기존 기능이 깨지지 않았는지 자동으로 확인
- **문서 역할** — `should_throwForbiddenException_whenRequesterIsNotAuthor` 같은 이름이 곧 "이 코드의 사양"
- **빠른 검증** — 매번 앱 띄우고 curl 치지 않아도 핵심 로직을 초 단위로 확인

### 1-2. 무엇을 테스트하나 — 이 프로젝트의 3가지 결

| 테스트 종류 | 무엇을 검증 | 이 프로젝트 파일 |
|-------------|-------------|------------------|
| 서비스 테스트 | 비즈니스 로직 (회원가입, 글 작성/수정 권한) | `AuthServiceTest`, `PostServiceTest`, `ProfileServiceTest` |
| 예외/응답 테스트 | 예외가 올바른 HTTP 상태·JSON으로 응답되는지 | `GlobalExceptionHandlerTest` |
| 컨텍스트 로딩 | 앱이 정상 기동되는지 (빈 설정 오류 없는지) | `BoardApplicationTests` |

---

## 2. 테스트 코드의 기본 골격

### 2-1. Given-When-Then (AAA) 패턴

모든 테스트는 3단계로 나뉜다:

```
Given  (준비)  — 테스트에 필요한 데이터/상태를 만든다
When   (실행)  — 검증하려는 동작을 딱 한 번 실행한다
Then   (검증)  — 결과가 기대와 같은지 확인한다
```

### 2-2. 가장 단순한 예 (PostServiceTest)

```java
@Test
void should_createPost_whenAuthorIsLoggedInUser() {
  // When — 글 작성
  PostResponse response =
      postService.create(board.getId(), author.getId(), new PostCreateRequest("제목", "내용"));

  // Then — 결과 확인
  assertThat(response.title()).isEqualTo("제목");
  assertThat(response.authorUsername()).isEqualTo("author1");
  assertThat(response.viewCount()).isZero();
}
```

(Given에 해당하는 `author`, `board` 준비는 아래 `@BeforeEach`에서 미리 해둔다.)

### 2-3. 테스트 메서드 이름 규칙

이 프로젝트는 `should_기대결과_when조건` 형식을 쓴다:

```
should_createPost_whenAuthorIsLoggedInUser   → 로그인 사용자가 작성하면 글이 생성된다
should_throwForbiddenException_whenRequesterIsNotAuthor → 작성자가 아니면 예외가 발생한다
```

> 이름만 읽어도 "무엇을 검증하는지" 알 수 있어야 좋은 테스트 이름이다.

---

## 3. 실제 테스트 3종 해부

### 3-1. 서비스 테스트 — @SpringBootTest + @Transactional

`test/.../post/PostServiceTest.java`:

```java
@SpringBootTest      // 실제 스프링 컨텍스트 전체를 띄운다 (진짜 빈 주입)
@Transactional       // 각 테스트가 끝나면 DB 변경을 자동 롤백
class PostServiceTest {

  @Autowired PostService postService;
  @Autowired UserRepository userRepository;
  @Autowired BoardRepository boardRepository;

  User author;
  User other;
  Board board;

  @BeforeEach          // 각 @Test 실행 전마다 호출 — 깨끗한 데이터 준비 (Given)
  void setUp() {
    author = userRepository.save(new User("author1", "author1@example.com", "encoded", Role.USER));
    other = userRepository.save(new User("other1", "other1@example.com", "encoded", Role.USER));
    board = boardRepository.save(new Board("자유게시판", "자유롭게 쓰는 곳"));
  }

  @Test
  void should_throwForbiddenException_whenRequesterIsNotAuthor() {
    // Given — author가 쓴 글
    PostResponse created =
        postService.create(board.getId(), author.getId(), new PostCreateRequest("제목", "내용"));

    // When + Then — 다른 사람(other)이 수정 시도하면 ForbiddenException
    assertThatThrownBy(() ->
        postService.update(created.id(), other.getId(), new PostUpdateRequest("수정", "수정")))
        .isInstanceOf(ForbiddenException.class);
  }
}
```

| 어노테이션 | 역할 |
|------------|------|
| `@SpringBootTest` | 실제 애플리케이션 컨텍스트를 통째로 띄워 진짜 서비스/리포지토리를 주입 |
| `@Transactional` | **각 테스트 끝에 롤백** → 테스트끼리 데이터가 섞이지 않음 (격리) |
| `@BeforeEach` | 매 테스트 직전에 실행 — 공통 준비물(Given)을 만든다 |
| `@Test` | 실제 테스트 메서드 하나 |

> **테스트는 H2 인메모리 DB로 돈다**
> `src/test/resources/application.yaml`이 MySQL 대신 H2를 쓰도록 설정되어 있어, MySQL이 꺼져 있어도 테스트가 실행된다. `@Transactional` 롤백 덕분에 매 테스트가 빈 DB에서 시작한다.

### 3-2. 예외/응답 테스트 — MockMvc로 HTTP 레벨 검증

`test/.../global/exception/GlobalExceptionHandlerTest.java`:

```java
@SpringBootTest
@AutoConfigureMockMvc      // 실제 서버 없이 HTTP 요청을 흉내 내는 MockMvc 준비
class GlobalExceptionHandlerTest {

  @Autowired MockMvc mockMvc;

  @Test
  void should_return404WithErrorResponse_whenPostNotFound() throws Exception {
    mockMvc.perform(get("/api/v1/posts/999999"))    // 없는 글 조회
        .andExpect(status().isNotFound())            // 404 인가?
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"))  // JSON의 code 필드
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void should_return400WithFieldErrors_whenSignupRequestInvalid() throws Exception {
    String invalidBody = """
        {"username": "ab", "email": "not-an-email", "password": "123", "nickname": ""}
        """;

    mockMvc.perform(post("/api/v1/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(invalidBody))
        .andExpect(status().isBadRequest())                    // 400
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.errors").isArray());            // 필드 오류 배열 존재
  }
}
```

| 구성 | 의미 |
|------|------|
| `@AutoConfigureMockMvc` | 실제 톰캣 없이 컨트롤러를 호출할 수 있는 `MockMvc` 제공 |
| `mockMvc.perform(get(...))` | HTTP 요청을 흉내 |
| `.andExpect(status().isNotFound())` | 응답 상태가 404인지 검증 |
| `jsonPath("$.code").value(...)` | 응답 JSON의 `code` 필드 값 검증 |

> 이 테스트는 [예외 처리 문서](EXCEPTION-HANDLING.md)에서 배운 `GlobalExceptionHandler`가 **실제로 404/400을 올바른 JSON으로 응답하는지**를 HTTP 레벨에서 확인한다.

### 3-3. 자주 쓰는 AssertJ 단언(assertion)

```java
assertThat(response.title()).isEqualTo("제목");      // 값이 같은가
assertThat(response.viewCount()).isZero();           // 0 인가
assertThat(user).isNotNull();                        // null 아닌가
assertThat(profileOptional).isPresent();             // Optional에 값 있는가
assertThat(passwordEncoder.matches(raw, hash)).isTrue();  // true 인가

// 예외 검증
assertThatThrownBy(() -> service.doSomething())
    .isInstanceOf(ForbiddenException.class);          // 이 예외가 던져지는가
```

---

## 4. 테스트 작성 단계 (실습 순서)

새 기능에 테스트를 붙이는 표준 순서:

```
1) 무엇을 검증할지 한 문장으로 정한다
   "작성자가 아니면 글 수정 시 ForbiddenException이 발생해야 한다"

2) 테스트 클래스/메서드를 만든다
   @SpringBootTest @Transactional + should_xxx_whenYyy 이름

3) Given — 필요한 데이터를 준비 (@BeforeEach 또는 메서드 안에서)

4) When — 검증할 동작을 한 번 실행

5) Then — assertThat / assertThatThrownBy 로 결과 확인

6) 테스트를 실행해 초록불(통과)을 확인

7) 일부러 코드를 깨뜨려 빨간불도 확인 (테스트가 진짜 동작하는지)
```

> **7번이 중요한 이유**: 항상 통과만 하는 테스트는 사실 아무것도 검증하지 않을 수 있다. 한 번은 일부러 실패시켜 "이 테스트가 진짜 잡아내는구나"를 확인하라.

---

## 5. 테스트 실행 방법

> 이 프로젝트는 Gradle을 쓴다. 명령은 프로젝트 루트(`board/`)에서 실행한다.

### 5-1. 전체(일괄) 테스트 — 가장 많이 쓰는 명령

```bash
./gradlew test
```

- `src/test/java` 아래 **모든 테스트**를 실행한다.
- 결과 요약이 터미널에 출력되고, 하나라도 실패하면 `BUILD FAILED`.

```bash
# 빌드까지 함께 (컴파일 + 테스트 + 검증)
./gradlew build
```

### 5-2. 클래스 하나만 실행

```bash
./gradlew test --tests "com.example.board.post.PostServiceTest"
```

### 5-3. 메서드 하나만 실행 (단위 테스트 하나씩)

```bash
./gradlew test --tests "com.example.board.post.PostServiceTest.should_createPost_whenAuthorIsLoggedInUser"
```

### 5-4. 패턴(와일드카드)으로 골라 실행

```bash
# 이름에 Service가 들어가는 모든 테스트 클래스
./gradlew test --tests "*ServiceTest"

# auth 패키지의 모든 테스트
./gradlew test --tests "com.example.board.auth.*"

# 메서드 이름이 throwForbidden 으로 시작하는 모든 테스트
./gradlew test --tests "*.should_throwForbidden*"
```

### 5-5. 캐시 무시하고 강제 재실행

Gradle은 바뀐 게 없으면 테스트를 건너뛰고 `UP-TO-DATE`로 표시한다. 무조건 다시 돌리려면:

```bash
./gradlew test --rerun-tasks
# 또는
./gradlew clean test
```

### 5-6. 명령 정리표

| 목적 | 명령 |
|------|------|
| 전체 테스트 | `./gradlew test` |
| 빌드 + 테스트 | `./gradlew build` |
| 클래스 하나 | `./gradlew test --tests "패키지.클래스명"` |
| 메서드 하나 | `./gradlew test --tests "패키지.클래스명.메서드명"` |
| 패턴 선택 | `./gradlew test --tests "*ServiceTest"` |
| 강제 재실행 | `./gradlew test --rerun-tasks` |
| 깨끗이 후 실행 | `./gradlew clean test` |

---

## 6. 결과 확인 방법

### 6-1. 터미널 출력

```
BUILD SUCCESSFUL in 6s     ← 전부 통과
BUILD FAILED               ← 하나라도 실패 (어떤 테스트가 왜 실패했는지 함께 출력)
```

### 6-2. HTML 리포트 (보기 좋은 상세 결과)

테스트를 한 번 실행하면 다음 파일이 생성된다:

```
build/reports/tests/test/index.html
```

브라우저로 열면 통과/실패 목록, 실패 원인, 소요 시간을 그래프로 볼 수 있다.

```bash
# macOS에서 바로 열기
open build/reports/tests/test/index.html
```

### 6-3. IDE에서 실행 (IntelliJ / VS Code)

- 테스트 메서드 왼쪽의 **▶ (초록 화살표)** 를 클릭하면 그 메서드 하나만 실행
- 클래스 이름 옆 화살표를 누르면 클래스 전체 실행
- 실패 시 빨간색으로 표시되고, 기대값/실제값 차이를 보여줌

> 입문 단계에서는 **IDE 화살표로 하나씩 돌려보며 감을 잡고**, CI나 최종 점검에서는 `./gradlew test`로 일괄 실행하는 방식을 추천한다.

---

## 7. 핵심 요약 한 장

```
┌─────────────────────────────────────────────────────────────────┐
│ 골격:  Given(준비) → When(실행) → Then(검증)                    │
│ 이름:  should_기대결과_when조건                                 │
│                                                                 │
│ 서비스 테스트:   @SpringBootTest @Transactional                 │
│                  assertThat(...) / assertThatThrownBy(...)       │
│ 예외 테스트:     @AutoConfigureMockMvc + MockMvc                │
│                  status().isNotFound(), jsonPath("$.code")       │
│                                                                 │
│ 실행:                                                           │
│   전체     ./gradlew test                                       │
│   클래스   ./gradlew test --tests "패키지.PostServiceTest"      │
│   메서드   ./gradlew test --tests "...PostServiceTest.메서드명" │
│   강제     ./gradlew test --rerun-tasks                         │
│                                                                 │
│ 결과:  터미널 BUILD SUCCESSFUL/FAILED                           │
│        리포트 build/reports/tests/test/index.html               │
└─────────────────────────────────────────────────────────────────┘
```

---

## 부록. 자주 나오는 질문 (FAQ)

| 질문 | 답변 요약 |
|------|-----------|
| 테스트에 MySQL이 필요한가? | 아니다. `src/test/resources/application.yaml`이 H2 인메모리를 쓰므로 DB 없이 실행된다. |
| `@Transactional`을 테스트에 붙이는 이유는? | 각 테스트가 끝나면 DB 변경을 롤백해 테스트끼리 영향을 주지 않게 하기 위해. |
| `should_throwForbiddenException...`는 어떻게 예외를 검증하나? | `assertThatThrownBy(() -> ...).isInstanceOf(ForbiddenException.class)`로 "그 예외가 던져지는지" 확인. |
| `./gradlew test`가 자꾸 UP-TO-DATE라며 안 돈다 | 바뀐 게 없으면 캐시를 쓴다. `--rerun-tasks` 또는 `clean test`로 강제 실행. |
| `@SpringBootTest`와 `@AutoConfigureMockMvc`의 차이는? | 전자는 컨텍스트를 띄워 빈을 주입(서비스 테스트). 후자를 더하면 HTTP 요청을 흉내 내는 MockMvc까지 사용 가능(컨트롤러/응답 테스트). |
| 실패한 테스트의 상세 원인은 어디서 보나? | 터미널 출력 또는 `build/reports/tests/test/index.html`. |
| 테스트가 항상 통과하는데 믿어도 되나? | 한 번은 일부러 코드를 깨서 빨간불을 확인하라. 실패를 못 잡는 테스트는 의미가 없다. |
