# 강의 자료 (강사용) — Spring Boot 게시판 만들기 [단계 1]

**과정명**: 입문자를 위한 Spring Boot 게시판 — 계층 구조 / JPA 단방향 연관관계 / HTTP 세션 인증
**대상**: Java 기본 문법(클래스/제네릭/람다)과 SQL 기초를 이해한 입문~초급 개발자
**총 소요시간**: 12시간 (90분 × 8 세션) 권장. 4일 분할 또는 2일 집중 가능.
**선수 지식**: Java 17+ 문법, HTTP 메서드/상태코드 개념, SQL SELECT/INSERT/UPDATE, Git clone/commit
**기술 스택**: Spring Boot 3.5.15, Java 21, Spring Data JPA, Spring Security(BCrypt only), MySQL 8 / 테스트는 H2

---

## 학습 목표

수강 종료 시 학생은 다음을 할 수 있어야 한다.

- (이해) Spring Boot의 Controller / Service / Repository 3계층 분리 이유를 설명할 수 있다.
- (이해) HTTP는 stateless인데 어떻게 로그인 상태를 유지하는지(JSESSIONID 쿠키 + 서버 세션) 설명할 수 있다.
- (이해) JPA 단방향 `@ManyToOne` / `@OneToOne` 매핑이 어떤 SQL을 발생시키는지 예측할 수 있다.
- (적용) Entity → Repository → Service → Controller → DTO 순으로 한 도메인을 처음부터 구현할 수 있다.
- (적용) `@RestControllerAdvice`로 도메인 예외를 HTTP 상태 코드로 변환하는 일관 처리 흐름을 작성할 수 있다.
- (적용) 인증(누구인가) vs 인가(무엇을 할 수 있는가)의 차이를 코드로 구분해 구현할 수 있다.
- (분석) `FetchType.LAZY`로 인한 N+1 문제를 직접 재현하고 `@EntityGraph` / fetch join으로 해결할 수 있다.

## 최종 산출물

- 회원가입/세션 로그인/로그아웃이 동작하는 게시판 백엔드 (`/api/v1/*`)
- ADMIN만 게시판을 만들 수 있고, USER는 게시글만 작성하며, 본인 글만 수정/삭제할 수 있는 인가 규칙
- 단방향 연관관계로 구성된 4개 Entity (User, UserProfile, Board, Post)
- 일관된 ErrorResponse를 내려주는 전역 예외 처리
- `@SpringBootTest` 기반 통합 테스트로 핵심 시나리오 검증

---

## 전체 강의 진행 순서 한눈에 보기

| 세션 | 주제 | 산출 패키지 / 파일 | 강의 포인트 |
|-----|------|----------------|-----------|
| 1 | 프로젝트 셋업 & 3계층 구조 이해 | `build.gradle`, `application.yaml`, `BoardApplication` | 왜 starter를 쓰는가, 왜 계층을 나누는가 |
| 2 | 공통 기반 (BaseTimeEntity, 예외 계층) | `global/entity`, `global/exception`, `global/config` | Auditing이 자동으로 동작하는 원리, 예외→HTTP 매핑 |
| 3 | User 도메인 & 회원가입 | `user/`, `auth/SignupRequest`, `auth/AuthService.signup` | 단방향 Entity, DTO 분리, BCrypt 해시 |
| 4 | HTTP 세션 로그인/로그아웃 | `auth/AuthController.login/logout`, `SessionConst` | stateless HTTP에서 상태를 유지하는 메커니즘 |
| 5 | UserProfile (1:1) & 내 프로필 API | `profile/` 전체 | 인증 정보와 부가 정보 분리, `@SessionAttribute`, `@EntityGraph` |
| 6 | Board 도메인 & ADMIN 인가 | `board/` 전체 | 인증과 인가의 차이, 401 vs 403 |
| 7 | Post 도메인 — 작성자 인가, 페이징, 조회수 | `post/` 전체 | `@ManyToOne` LAZY, N+1, dirty checking, `Pageable` |
| 8 | 테스트 & 시연 & 단계 2 예고 | `src/test/...`, 시연 시나리오 | 통합 테스트 작성법, 코드 정리, 다음 단계 동기부여 |

> **순서 결정 원칙 — 의존성 역추적:** Post는 Board와 User에 FK가 걸려있다 → Board는 ADMIN(User)에 의존한다 → User는 회원가입 흐름이 필요하다 → 회원가입은 BCrypt(SecurityConfig) + 예외 처리에 의존한다 → 모든 도메인은 `BaseTimeEntity`를 상속한다 → 모두 application.yaml의 DataSource 연결을 전제로 한다. 따라서 **셋업 → 공통 → User → Auth → Profile → Board → Post → 테스트** 순으로 쌓는 것이 컴파일·실행 모두 가능한 유일한 순서다.

---

## Session 1. 프로젝트 셋업 & 3계층 구조 이해 (90분)

### 1-1. 왜 Spring Boot인가? (15분)

핵심 메시지 한 줄: **"Spring Boot는 '의존성만 추가하면 즉시 실행되는 Spring 애플리케이션'을 만든다."**

Spring 시절 web.xml/applicationContext.xml로 100줄을 써야 했던 셋업이 starter 한 줄로 끝난다. Tomcat이 jar 안에 포함되어 `java -jar`만으로 서버가 뜬다.

> **강의 포인트**
> - **WHY** — 입문자는 환경 설정에서 좌절한다. Boot가 그 진입 장벽을 없앤다.
> - **WHAT** — `spring-boot-starter-web`을 의존성에 추가하는 순간 `@SpringBootApplication`의 자동 구성이 Tomcat + Jackson + Spring MVC를 묶어 띄운다.
> - **HOW** — 의존성 목록만 보면 이 앱이 무엇을 할 수 있는지 짐작할 수 있어야 한다.
> - **PITFALL** — "왜 잘 되지?"로 끝나면 안 된다. 한 번은 `@SpringBootApplication`을 풀어서 보여줘라(`@EnableAutoConfiguration` + `@ComponentScan` + `@Configuration`).

### 1-2. `build.gradle` 라이브 작성 (20분)

```gradle
plugins {
  id 'java'
  id 'org.springframework.boot' version '3.5.15'
  id 'io.spring.dependency-management' version '1.1.7'
}

group = 'com.example'
version = '0.0.1-SNAPSHOT'

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

repositories { mavenCentral() }

dependencies {
  implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
  implementation 'org.springframework.boot:spring-boot-starter-security'
  implementation 'org.springframework.boot:spring-boot-starter-validation'
  implementation 'org.springframework.boot:spring-boot-starter-web'
  compileOnly 'org.projectlombok:lombok'
  runtimeOnly 'com.mysql:mysql-connector-j'
  annotationProcessor 'org.projectlombok:lombok'
  testImplementation 'org.springframework.boot:spring-boot-starter-test'
  testImplementation 'org.springframework.security:spring-security-test'
  testRuntimeOnly 'com.h2database:h2'
  testCompileOnly 'org.projectlombok:lombok'
  testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
  testAnnotationProcessor 'org.projectlombok:lombok'
}

tasks.named('test') { useJUnitPlatform() }
```

각 starter가 무엇을 끌어오는지 짧게 짚는다.

| starter | 끌어오는 것 | 강의 시점 등장 |
|---------|-----------|-----------|
| web | Spring MVC + Tomcat + Jackson | 1세션 |
| data-jpa | Hibernate + HikariCP + Spring Data | 3세션 |
| security | Spring Security 필터체인 + BCrypt | 4세션 |
| validation | Bean Validation 3 (Hibernate Validator) | 3세션 |

> **강의 포인트**
> - **WHY** — `compileOnly`/`annotationProcessor`가 Lombok에 둘 다 필요한 이유: 컴파일 시 코드 생성 + 런타임에는 불필요.
> - **PITFALL** — 학생들이 자주 빠뜨리는 함정: `runtimeOnly 'com.mysql:mysql-connector-j'`. JDBC 드라이버는 런타임에만 필요하므로 컴파일 의존성에 넣을 필요가 없다.

### 1-3. `application.yaml` & DB 연결 (15분)

```yaml
spring:
  application:
    name: board
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:board}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:1234}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update      # 강의 편의용. 운영은 validate + Flyway
    open-in-view: false
    properties:
      hibernate:
        format_sql: true

logging:
  level:
    org.hibernate.SQL: debug
```

> **강의 포인트**
> - **WHY** — `${DB_PASSWORD:1234}` 패턴은 "기본값은 1234, 환경변수로 덮어쓸 수 있다"는 뜻. 운영에서는 절대 평문 비밀번호를 yaml에 두지 않는다.
> - **WHAT** — `ddl-auto: update`는 Entity 변경을 따라 ALTER TABLE을 시도한다. 편하지만 컬럼 삭제는 안 되고, 운영에 쓰면 사고난다.
> - **HOW** — `open-in-view: false`를 처음부터 켜라. true면 Controller까지 영속성 컨텍스트가 살아있어 N+1을 늦게 발견한다.
> - **PITFALL** — `format_sql: true` + `org.hibernate.SQL: debug`는 학습용 황금 조합. 운영에서는 끄거나 INFO로.

### 1-4. 3계층 구조의 의미 (25분)

`com.example.board.<domain>` 패키지 1개 안에 Controller/Service/Repository/Entity/dto를 함께 둔다. 도메인별 패키징(Package-by-Feature)이 입문자에게도 가독성이 좋다.

```
com.example.board
├── BoardApplication.java      // @SpringBootApplication
├── global/                    // 횡단 관심사 (공통)
│   ├── config/                // SecurityConfig, WebConfig, JpaAuditingConfig, DataInitializer
│   ├── entity/                // BaseTimeEntity
│   └── exception/             // ErrorCode, BusinessException 계층, GlobalExceptionHandler
├── auth/                      // 회원가입/로그인/로그아웃 흐름
├── user/                      // User Entity + Role + Repository
├── profile/                   // UserProfile (User와 1:1)
├── board/                     // 게시판
└── post/                      // 게시글
```

**나쁜 예 (Before):**
```java
// ❌ Controller가 모든 일을 한다 — DB 접근부터 비밀번호 해시까지
@RestController
public class BadController {
  @PersistenceContext EntityManager em;
  @PostMapping("/signup")
  public Map<String,Object> signup(@RequestBody Map<String,String> body) {
    // 비즈니스 로직 + DB 접근 + 직렬화가 한 메서드에 섞임
    // 테스트가 불가능하고, 재사용이 안 되며, 트랜잭션 경계가 불명확하다
  }
}
```

**좋은 예 (After):**
```java
// ✅ Controller는 요청 받기/응답하기만. 비즈니스 규칙은 Service. DB는 Repository.
@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService authService;
  @PostMapping("/signup")
  @ResponseStatus(HttpStatus.CREATED)
  public UserResponse signup(@Valid @RequestBody SignupRequest request) {
    return authService.signup(request);
  }
}
```

> **강의 포인트**
> - **WHY** — 계층을 나눠야 트랜잭션 경계(`@Transactional`)를 Service에 명확히 둘 수 있다. Controller에 트랜잭션을 걸면 입출력 직렬화도 트랜잭션 안에 들어와 lazy 로딩 사고가 늘어난다.
> - **WHAT** — Controller(HTTP) ↔ Service(비즈니스 규칙 + 트랜잭션) ↔ Repository(DB) — 각 계층은 한 방향으로만 호출한다.
> - **PITFALL** — Controller에서 Repository를 직접 호출하지 마라. 트랜잭션이 없는 채로 lazy 필드에 접근하면 `LazyInitializationException`이 터진다.

### 다음 차시 예고
"지금은 모든 Entity에 createdAt/updatedAt을 일일이 쓸 수도 있지만, 도메인 4개에 8번 반복하면 코드가 더러워진다. 다음 시간엔 `BaseTimeEntity` 하나로 모든 Entity가 시각을 자동으로 갖게 만든다."

---

## Session 2. 공통 기반 — BaseTimeEntity, 예외 계층, Security/Web/Auditing 설정 (90분)

### 2-1. BaseTimeEntity + JPA Auditing (20분)

```java
// global/entity/BaseTimeEntity.java
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {
  @CreatedDate
  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;
  @LastModifiedDate
  @Column(nullable = false)
  private LocalDateTime updatedAt;
}
```

```java
// global/config/JpaAuditingConfig.java
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {}
```

> **강의 포인트**
> - **WHY** — 시각 컬럼은 모든 테이블에 똑같이 들어간다. 중복을 줄이는 표준 방식이 `@MappedSuperclass`.
> - **WHAT** — `@EnableJpaAuditing`이 등록한 `AuditingEntityListener`가 persist/update 직전 콜백에서 시각을 채워준다.
> - **PITFALL** — `@EnableJpaAuditing`을 안 붙이면 컴파일은 되지만 `createdAt`이 null이 되어 NOT NULL 제약에 걸린다. "왜 INSERT가 실패하지?"의 가장 흔한 원인.

### 2-2. 예외 계층 설계 (25분)

먼저 **나쁜 예부터 보여준다.**

```java
// ❌ Controller마다 try-catch + 상태코드를 직접 결정
@PostMapping("/login")
public ResponseEntity<?> login(@RequestBody LoginRequest r) {
  try {
    return ResponseEntity.ok(authService.login(r));
  } catch (RuntimeException e) {
    if (e.getMessage().contains("not found")) return ResponseEntity.status(401).body(...);
    if (e.getMessage().contains("password")) return ResponseEntity.status(401).body(...);
    // 메시지로 분기? 형광등 끄듯이 무너진다
    return ResponseEntity.internalServerError().build();
  }
}
```

**좋은 예 (실제 구현):**

```java
// global/exception/ErrorCode.java — HTTP 상태와 메시지를 한 곳에서 관리
public enum ErrorCode {
  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
  PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "프로필을 찾을 수 없습니다."),
  BOARD_NOT_FOUND(HttpStatus.NOT_FOUND, "게시판을 찾을 수 없습니다."),
  POST_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."),
  DUPLICATE_USERNAME(HttpStatus.CONFLICT, "이미 사용 중인 username입니다."),
  DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 email입니다."),
  DUPLICATE_BOARD_NAME(HttpStatus.CONFLICT, "이미 존재하는 게시판 이름입니다."),
  NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 nickname입니다."),
  POST_ACCESS_DENIED(HttpStatus.FORBIDDEN, "게시글에 대한 권한이 없습니다."),
  ADMIN_ONLY(HttpStatus.FORBIDDEN, "관리자만 수행할 수 있습니다."),
  LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
  LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "username 또는 password가 올바르지 않습니다."),
  INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");
  // ...
}
```

```java
// global/exception/BusinessException.java + 4개 자식 클래스
public class BusinessException extends RuntimeException {
  private final ErrorCode errorCode;
  public BusinessException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }
}
public class NotFoundException extends BusinessException { ... }
public class DuplicateException extends BusinessException { ... }
public class UnauthorizedException extends BusinessException { ... }
public class ForbiddenException extends BusinessException { ... }
```

```java
// global/exception/ErrorResponse.java — JSON으로 내려갈 형식
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String code, String message, LocalDateTime timestamp,
    List<FieldErrorDetail> errors) {
  public record FieldErrorDetail(String field, String reason) {}
  public static ErrorResponse of(ErrorCode c) { ... }
  public static ErrorResponse of(ErrorCode c, List<FieldErrorDetail> errors) { ... }
}
```

```java
// global/exception/GlobalExceptionHandler.java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
    ErrorCode errorCode = e.getErrorCode();
    log.warn("BusinessException: code={}, message={}", errorCode.name(), e.getMessage());
    return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
  }
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
    List<ErrorResponse.FieldErrorDetail> errors = e.getBindingResult().getFieldErrors().stream()
        .map(err -> new ErrorResponse.FieldErrorDetail(err.getField(), err.getDefaultMessage()))
        .toList();
    return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
        .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, errors));
  }
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception e) {
    log.error("Unexpected exception", e);
    return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
        .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
  }
}
```

> **강의 포인트**
> - **WHY** — Service는 "무엇이 잘못됐는가"만 신경 쓰고 HTTP는 모른다. 변환은 한 곳에서.
> - **WHAT** — `@RestControllerAdvice`는 모든 컨트롤러의 예외를 가로채는 AOP 기반 핸들러.
> - **HOW** — ErrorCode → 상태코드 매핑이 enum에 박혀있어 새 케이스를 추가할 때 한 줄만 추가하면 된다.
> - **PITFALL** — 마지막 `Exception.class` 핸들러에서 `e.getMessage()`를 응답에 그대로 내보내지 마라. 스택트레이스나 SQL 단서가 노출될 수 있다. 로그만 남기고 응답은 "내부 오류"로 통일.

### 2-3. SecurityConfig — "starter만 넣으면 모든 게 잠긴다" (20분)

이 세션 최대 함정. starter security만 추가하면 모든 요청이 401이 된다. 일부러 잠시 SecurityConfig 없이 띄워보고 401을 학생들에게 보여줘라.

```java
// global/config/SecurityConfig.java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }
}
```

> **강의 포인트**
> - **WHY** — Spring Security를 끄고 BCrypt만 빌려쓰는 구성이다. 인증/인가는 이번 단계에서 손으로 짠다 — 그래야 메커니즘을 안다.
> - **WHAT** — `permitAll()`은 필터체인이 통과시키되 SecurityContext는 비어있다. 인증/인가는 우리 코드가 직접 한다.
> - **HOW** — `csrf().disable()`은 JSON API + 세션 학습용. 브라우저 + 세션 + 폼 운영에선 절대 끄지 않는다.
> - **PITFALL** — "왜 단계 1에서 BCryptPasswordEncoder만 빌려쓰지?" → 비밀번호 해시는 직접 구현하면 안 되는 영역(salt, 반복 횟수, timing attack). 보안은 검증된 라이브러리에 맡긴다.

### 2-4. WebConfig — Page 직렬화 안정화 (10분)

```java
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
public class WebConfig {}
```

> **강의 포인트**
> - **WHY** — Spring Data 3.3부터 `Page` 직접 직렬화가 deprecated 경고를 띄운다. `PagedModel`로 안정 JSON 구조를 보장한다.
> - **PITFALL** — 7세션에서 `Page<PostListResponse>`를 그냥 반환할 거다. 이 설정이 없으면 응답 JSON 키가 버전마다 달라진다.

### 2-5. DataInitializer — ADMIN 시드 (15분)

```java
@Slf4j @Component @Profile("!prod") @RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
  private final UserRepository userRepository;
  private final UserProfileRepository userProfileRepository;
  private final PasswordEncoder passwordEncoder;
  @Value("${app.admin.username:admin}") private String adminUsername;
  @Value("${app.admin.password:admin1234}") private String adminPassword;

  @Override
  @Transactional
  public void run(String... args) {
    if (userRepository.existsByUsername(adminUsername)) return;
    User admin = new User(adminUsername, "admin@example.com",
        passwordEncoder.encode(adminPassword), Role.ADMIN);
    userRepository.save(admin);
    userProfileRepository.save(new UserProfile(admin, "관리자", "강의용 관리자 계정"));
    log.info("ADMIN 시드 계정 생성: username={}", adminUsername);
  }
}
```

> **강의 포인트**
> - **WHY** — 매번 회원가입으로 ADMIN을 만들 수 없다. 시연 직전 admin/admin1234가 항상 있도록 보장.
> - **WHAT** — `CommandLineRunner`는 ApplicationContext가 뜨고 나서 한 번 실행된다.
> - **PITFALL** — `@Profile("!prod")` — 운영 프로파일에선 절대 돌지 않게. 실제 사고: 운영 DB에 admin/admin1234가 만들어진다.

**실습 (15분):**
> 강사 코드를 보면서 학생이 직접 패키지 구조를 만들고 위 파일들을 작성한다. 단, `User`, `UserProfile`은 아직 존재하지 않으므로 `DataInitializer`는 일단 주석 처리하거나 비워둔다. (3, 5세션에서 활성화)

### 다음 차시 예고
"지금은 빈 껍데기다. 다음 시간에 User Entity와 회원가입 API를 붙이고, 한 명의 사용자를 DB에 INSERT 해본다. 비밀번호는 어떻게 저장하는지 BCrypt를 직접 다뤄볼 거다."

---

## Session 3. User 도메인 & 회원가입 (90분)

### 3-1. User Entity & Role enum (20분)

```java
// user/Role.java
public enum Role { USER, ADMIN }

// user/User.java
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(nullable = false, unique = true, length = 50)
  private String username;
  @Column(nullable = false, unique = true, length = 100)
  private String email;
  @Column(nullable = false)
  private String password;      // BCrypt 해시
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Role role;
  public User(String username, String email, String password, Role role) {
    this.username = username; this.email = email;
    this.password = password; this.role = role;
  }
}
```

> **강의 포인트**
> - **WHY** — `@NoArgsConstructor(access = PROTECTED)`: JPA는 리플렉션으로 빈 생성자가 필요하지만, 외부 코드는 못 쓰게 막는다(불변성 보호).
> - **WHAT** — `@Enumerated(EnumType.STRING)`: 절대 `ORDINAL`을 쓰지 마라. 순서 바뀌면 DB가 무너진다.
> - **HOW** — `@Table(name = "users")`: MySQL/PostgreSQL에서 `user`는 예약어. 항상 복수형 + 예약어 회피.
> - **PITFALL** — 연관관계 없음. "그럼 User가 가진 Post 목록은?" → 단방향만 쓴다. 필요하면 PostRepository에서 조회. (강의 핵심 결정)

### 3-2. UserRepository (10분)

```java
// user/UserRepository.java
public interface UserRepository extends JpaRepository<User, Long> {
  Optional<User> findByUsername(String username);
  boolean existsByUsername(String username);
  boolean existsByEmail(String email);
}
```

> **강의 포인트**
> - **WHY** — Spring Data가 메서드 이름을 파싱해 쿼리를 자동 생성. `findByXxx`, `existsByXxx`, `countByXxx` 패턴.
> - **PITFALL** — INSERT 직전에 `existsBy`로 중복 체크하면 동시 요청에서 race condition 가능. 그래도 일단 안내하는 정도로 충분. 강의 단계에선 DB UNIQUE 제약이 백업.

### 3-3. UserResponse DTO & 왜 Entity를 노출하지 않는가 (15분)

```java
// user/dto/UserResponse.java
public record UserResponse(Long id, String username, String email, String role) {
  public static UserResponse from(User user) {
    return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole().name());
  }
}
```

**나쁜 예 (Before):**
```java
// ❌ Entity 직접 반환
@PostMapping("/signup")
public User signup(@RequestBody SignupRequest r) {
  return authService.signup(r);  // password 해시까지 응답에 노출됨
}
```

**좋은 예 (After):** record `UserResponse`로 변환 후 반환.

> **강의 포인트**
> - **WHY** — Entity를 직렬화하면 (1) password 해시 노출, (2) lazy 필드 건드려 추가 쿼리, (3) API 스펙이 DB 스키마에 묶이는 3중 사고.
> - **WHAT** — record는 Java 14+ 정식 기능. 불변 데이터 운반체로 DTO에 완벽.
> - **HOW** — `static from(Entity)` 변환 메서드 컨벤션을 정착시킨다.

### 3-4. SignupRequest — Bean Validation (10분)

```java
// auth/dto/SignupRequest.java
public record SignupRequest(
    @NotBlank @Size(min = 4, max = 50) String username,
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8) String password,
    @NotBlank @Size(max = 50) String nickname
) {}
```

> **강의 포인트**
> - **WHY** — 검증을 컨트롤러 시작점에서 차단해야 Service까지 더러운 데이터가 흘러들지 않는다.
> - **WHAT** — `@Valid`가 `@RequestBody`에 붙으면 ConstraintViolation 발생 시 `MethodArgumentNotValidException` → 2세션에서 만든 핸들러가 400으로 변환.
> - **PITFALL** — `@NotNull` vs `@NotBlank` 혼동. 문자열은 `@NotBlank`(공백도 거른다).

### 3-5. AuthService.signup — 트랜잭션, BCrypt, 1:1 함께 생성 (20분)

⚠ 이 시점에 UserProfile은 아직 만들지 않았다. **두 가지 진행 방식 중 택일:**
- **(권장)** 5세션의 UserProfile Entity를 먼저 보여주고 회원가입을 한 번에 완성한다.
- 또는 회원가입을 User만 저장하는 형태로 작성한 뒤 5세션에서 한 줄 추가하는 흐름.

본 강의 자료는 (권장) 흐름으로 작성한다. 즉 3세션 후반에 `UserProfile` Entity까지 미리 보여준다.

```java
// auth/AuthService.java
@Service @RequiredArgsConstructor
public class AuthService {
  private final UserRepository userRepository;
  private final UserProfileRepository userProfileRepository;
  private final PasswordEncoder passwordEncoder;

  @Transactional
  public UserResponse signup(SignupRequest request) {
    if (userRepository.existsByUsername(request.username()))
      throw new DuplicateException(ErrorCode.DUPLICATE_USERNAME);
    if (userRepository.existsByEmail(request.email()))
      throw new DuplicateException(ErrorCode.DUPLICATE_EMAIL);
    if (userProfileRepository.existsByNickname(request.nickname()))
      throw new DuplicateException(ErrorCode.NICKNAME_DUPLICATED);

    User user = userRepository.save(new User(
        request.username(), request.email(),
        passwordEncoder.encode(request.password()), Role.USER));
    userProfileRepository.save(new UserProfile(user, request.nickname(), null));
    return UserResponse.from(user);
  }
}
```

> **강의 포인트**
> - **WHY** — `@Transactional`이 메서드 시작에 트랜잭션을 열고, 정상 종료 시 커밋, RuntimeException 시 롤백. User만 저장되고 Profile이 실패하면 둘 다 롤백된다 → 1:1 무결성 보장.
> - **WHAT** — `passwordEncoder.encode()`: BCrypt는 매번 다른 해시를 반환한다(salt 자동 포함). 그래서 같은 비밀번호도 DB에서는 매번 다르게 보인다.
> - **HOW** — `@Transactional`은 Service 메서드에만. Controller에도 Repository에도 붙이지 않는다.
> - **PITFALL** — 입문자가 가장 많이 빠지는 함정: Service 메서드에 `@Transactional`을 안 붙이면 `dirty checking`이 안 먹는다(7세션 조회수 증가에서 직접 보여줌).

### 3-6. AuthController.signup — Controller 레이어 (15분)

```java
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
  private final AuthService authService;
  @PostMapping("/signup")
  @ResponseStatus(HttpStatus.CREATED)
  public UserResponse signup(@Valid @RequestBody SignupRequest request) {
    return authService.signup(request);
  }
  // login, logout은 4세션에서 추가
}
```

시연:
```bash
curl -i -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"password123","nickname":"앨리스"}'
# 201 Created + UserResponse JSON
```

검증 실패 시연:
```bash
curl -i -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"username":"a","email":"bad","password":"1","nickname":""}'
# 400 + errors 배열
```

**실습 (10분):**
> 학생이 직접 회원가입 호출 후 MySQL에서 `select * from users;`로 password 컬럼이 BCrypt 해시인지 확인한다. `$2a$10$...` 패턴이어야 한다.

### 다음 차시 예고
"회원가입은 됐는데, 로그인 후 `/api/v1/profiles/me`를 호출하면 서버는 '너 누구야?'를 어떻게 알지? HTTP는 매 요청마다 새로운 연결처럼 동작하는 stateless인데. 다음 시간엔 HTTP 세션이라는 메커니즘을 직접 다뤄본다."

---

## Session 4. HTTP 세션 로그인/로그아웃 (90분)

### 4-1. HTTP는 stateless — 그럼 어떻게 로그인 상태를 유지하나? (15분)

**칠판 그림으로 설명:**
```
[브라우저]                          [서버]
  ── POST /login (id,pw) ──────→
                                  세션 생성 (session id = ABC123)
                                  서버 메모리에 { ABC123 → loginUserId=1 }
  ←── Set-Cookie: JSESSIONID=ABC123 ──
  (브라우저가 쿠키 저장)
  ── GET /me  Cookie: JSESSIONID=ABC123 ──→
                                  쿠키로 ABC123 찾아서 → loginUserId=1
                                  → "ID 1번 사용자의 프로필 반환"
  ← 200 OK
```

> **강의 포인트**
> - **WHY** — HTTP 요청은 본래 독립적이다. 인증 정보를 어딘가에 저장하지 않으면 매 요청마다 다시 비밀번호를 보내야 한다.
> - **WHAT** — 세션 = 서버 쪽 저장소. JSESSIONID 쿠키 = 그 저장소를 가리키는 열쇠.
> - **PITFALL** — 세션 데이터를 클라이언트에 저장하지 마라. 서버에만 두고 키만 쿠키로 교환. (반대로 했다가 변조당하는 사고가 흔하다.)

### 4-2. LoginRequest + SessionConst (5분)

```java
// auth/dto/LoginRequest.java
public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

// auth/SessionConst.java
public final class SessionConst {
  public static final String LOGIN_USER_ID = "loginUserId";
  private SessionConst() {}
}
```

> **강의 포인트**
> - **WHY** — 세션 attribute 키를 문자열로 흩뿌리면 오타가 침묵의 버그로 변한다. 한 곳 상수로.

### 4-3. AuthService.login — 인증만 담당 (15분)

```java
@Transactional(readOnly = true)
public UserResponse login(LoginRequest request) {
  User user = userRepository.findByUsername(request.username())
      .orElseThrow(() -> new UnauthorizedException(ErrorCode.LOGIN_FAILED));
  if (!passwordEncoder.matches(request.password(), user.getPassword())) {
    throw new UnauthorizedException(ErrorCode.LOGIN_FAILED);
  }
  return UserResponse.from(user);
}
```

> **강의 포인트**
> - **WHY** — username 미존재와 비밀번호 불일치를 **같은 메시지(LOGIN_FAILED)**로 응답한다. "그 username은 존재하지 않습니다"는 user enumeration 공격에 이용된다.
> - **WHAT** — `passwordEncoder.matches(평문, 해시)`: BCrypt는 해시에서 salt를 꺼내 다시 해싱한 결과를 비교한다.
> - **HOW** — `@Transactional(readOnly = true)`: 읽기 전용 트랜잭션. dirty checking을 건너뛰어 살짝 빠르다.
> - **PITFALL** — Service 안에서 HttpSession에 손대지 마라. Service는 "이 사람이 누구인지" 확인만 하고, "세션에 저장" 결정은 Controller(웹 계층)가 한다. 단계 2에서 JWT로 바꿀 때 Service 코드를 안 건드릴 수 있는 핵심 분리.

### 4-4. AuthController — 세션에 저장 (20분)

```java
@PostMapping("/login")
public UserResponse login(@Valid @RequestBody LoginRequest request, HttpSession session) {
  UserResponse user = authService.login(request);
  session.setAttribute(SessionConst.LOGIN_USER_ID, user.id());
  return user;
}

@PostMapping("/logout")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void logout(HttpServletRequest request) {
  HttpSession session = request.getSession(false);  // false: 없으면 만들지 않음
  if (session != null) session.invalidate();
}
```

> **강의 포인트**
> - **WHY** — `getSession(false)`: 이미 비로그인 상태에서 logout 호출되면 새 세션을 만들 이유가 없다. true가 기본값이라 신중히.
> - **WHAT** — `session.invalidate()`: 서버 측 세션 저장소에서 데이터를 지운다. 클라이언트 쿠키는 남아있지만 키가 무효 → 사실상 로그아웃.
> - **HOW** — `@ResponseStatus(NO_CONTENT)`: 본문 없는 204를 명시.
> - **PITFALL** — "로그아웃 했는데 쿠키가 남아있어요"라는 질문이 매번 나온다. 답: 쿠키는 클라이언트 정리 영역이지만 서버 세션이 죽었기 때문에 보안적으로 무의미하다.

### 4-5. 시연: JSESSIONID 발급 → 재사용 → 무효화 (25분)

**Step 1. 회원가입:**
```bash
curl -i -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"password123","nickname":"앨리스"}'
```

**Step 2. 로그인 — 응답 헤더 `Set-Cookie: JSESSIONID=...`를 학생들에게 보여줘라:**
```bash
curl -i -c cookie.txt -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'
# 응답:
# HTTP/1.1 200 OK
# Set-Cookie: JSESSIONID=8B2C9F...; Path=/; HttpOnly
# {"id":1,"username":"alice",...}
cat cookie.txt   # JSESSIONID 값 확인
```

**Step 3. 쿠키 포함 요청 — 서버가 alice를 인식한다:**
```bash
curl -i -b cookie.txt http://localhost:8080/api/v1/profiles/me
# 200 OK + alice 프로필 (5세션에서 활성화)
```

**Step 4. 쿠키 없이 요청 — 401:**
```bash
curl -i http://localhost:8080/api/v1/profiles/me
# 401 + {"code":"LOGIN_REQUIRED","message":"로그인이 필요합니다."}
```

**Step 5. 로그아웃 → 같은 쿠키로 재요청 → 401:**
```bash
curl -i -b cookie.txt -X POST http://localhost:8080/api/v1/auth/logout
# 204
curl -i -b cookie.txt http://localhost:8080/api/v1/profiles/me
# 401 (서버 세션 무효화됨)
```

> **강의 포인트**
> - **PITFALL** — 학생들이 Postman으로 시연하면 자동으로 쿠키 처리되어 흐름을 못 본다. 최소 한 번은 curl `-c` / `-b`로 쿠키가 어떻게 오가는지 눈으로 보게 하라.

### 4-6. (선택) 실습 — 잘못된 비밀번호 (10분)

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"wrong"}'
# 401 LOGIN_FAILED — 메시지가 "username 또는 password가 올바르지 않습니다"인지 확인
```

### 다음 차시 예고
"이제 alice는 서버가 자신을 인식한다. 하지만 '내 프로필'을 어떻게 가져올까? UserProfile은 User와 1:1로 분리되어 있다. 다음 시간엔 1:1 매핑과 `@SessionAttribute`로 로그인 사용자 정보를 컨트롤러에 꽂는 법을 본다."

---

## Session 5. UserProfile (1:1) & 내 프로필 API (90분)

### 5-1. 왜 User와 UserProfile을 분리하는가? (15분)

**비교 표:**

| 관점 | 한 테이블 | User + UserProfile 분리 |
|------|-----------|---------------------|
| 인증 시 password 노출 위험 | 모든 컬럼 함께 가져옴 | User만 조회로 격리 |
| 프로필 자주 수정 | 인증 컬럼까지 락 | UserProfile만 영향 |
| 컬럼 추가(자기소개, 전화번호…) | users 테이블 폭증 | UserProfile에서 확장 |
| 외부 노출 API | 골라 보낼 필드 많음 | UserProfile만 노출 |

> **강의 포인트**
> - **WHY** — 관심사 분리. 인증 데이터는 보수적으로(거의 안 바뀜), 프로필은 자주 바뀌고 화면용.
> - **WHAT** — 실무에서는 OAuth 추가 시 user 테이블엔 social_id만, 닉네임/아바타는 profile에 두는 패턴이 표준.

### 5-2. UserProfile Entity — 단방향 @OneToOne (20분)

```java
@Entity
@Table(name = "user_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProfile extends BaseTimeEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false, unique = true)
  private User user;

  @Column(nullable = false, unique = true, length = 50)
  private String nickname;
  @Column(length = 500) private String bio;
  @Column(length = 20) private String phoneNumber;
  private LocalDate birthDate;
  @Column(length = 500) private String profileImageUrl;

  public UserProfile(User user, String nickname, String bio) {
    this.user = user; this.nickname = nickname; this.bio = bio;
  }
  public void update(String nickname, String bio, String phoneNumber,
                     LocalDate birthDate, String profileImageUrl) {
    this.nickname = nickname; this.bio = bio;
    this.phoneNumber = phoneNumber; this.birthDate = birthDate;
    this.profileImageUrl = profileImageUrl;
  }
}
```

> **강의 포인트**
> - **WHY** — `@OneToOne` 단방향: UserProfile만 `user`를 안다. User는 자기 프로필을 모른다(필요하면 Repository 조회). 양방향이었다면 순환참조 + mappedBy + 편의 메서드 학습 부담.
> - **WHAT** — `@JoinColumn(name = "user_id", unique = true)`: FK가 unique이므로 1:1이 강제된다.
> - **HOW** — `fetch = LAZY`: 프로필을 가져올 때 항상 User가 필요한 건 아니다. 필요하면 `@EntityGraph`로 조인.
> - **PITFALL** — `@OneToOne`의 비주인 쪽은 LAZY가 안 먹는 함정이 있다. 우리는 주인(=FK 보유) 쪽이라 LAZY가 정확히 동작.

### 5-3. UserProfileRepository — @EntityGraph로 N+1 차단 (15분)

```java
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
  @EntityGraph(attributePaths = "user")
  Optional<UserProfile> findByUserId(Long userId);
  boolean existsByNickname(String nickname);
  boolean existsByNicknameAndUserIdNot(String nickname, Long userId);
}
```

**실험으로 보여줘라:**
- `@EntityGraph` 없이 호출 → ProfileResponse.from에서 `profile.getUser().getUsername()` 접근 시 추가 SELECT 한 번 더 발생 (SQL 로그로 확인).
- `@EntityGraph(attributePaths = "user")` 추가 → JOIN으로 한 방에 가져옴.

> **강의 포인트**
> - **WHY** — N+1은 이론으로 외우면 안 잊는다. 실제 로그를 보여줘야 학생이 평생 기억한다.
> - **WHAT** — `@EntityGraph`는 Hibernate에 "이 메서드는 이 연관관계도 함께 fetch해줘"라고 선언적으로 알려준다.
> - **HOW** — `existsByNicknameAndUserIdNot`: 자기 자신은 제외하고 닉네임 중복 검사. 수정 시 닉네임을 안 바꾸는 경우를 자연스럽게 통과시키기 위함.

### 5-4. ProfileResponse & ProfileUpdateRequest (10분)

```java
public record ProfileResponse(
    Long userId, String username, String email, String nickname,
    String bio, String phoneNumber, LocalDate birthDate, String profileImageUrl) {
  public static ProfileResponse from(UserProfile p) { ... }
}
```

```java
public record ProfileUpdateRequest(
    @NotBlank @Size(max = 50) String nickname,
    @Size(max = 500) String bio,
    @Pattern(regexp = "^01[0-9]-\\d{3,4}-\\d{4}$") String phoneNumber,
    @Past LocalDate birthDate,
    @Size(max = 500) String profileImageUrl) {}
```

> **강의 포인트**
> - **WHY** — `@Pattern`은 **null이면 통과한다**. 선택 입력 필드에 `@NotBlank` 없이 `@Pattern`만 붙이면 "비어있으면 검증 skip, 값이 있으면 형식 검증"이 자연스럽게 표현된다.
> - **PITFALL** — `@NotNull`을 같이 붙이면 입력 안 한 사람도 400으로 거절된다. 선택 필드의 검증 의도를 정확히 표현하라.

### 5-5. ProfileService — 본인 글만 닉네임 중복 검사 제외 (15분)

```java
@Service @RequiredArgsConstructor
public class ProfileService {
  private final UserProfileRepository userProfileRepository;

  @Transactional(readOnly = true)
  public ProfileResponse getMyProfile(Long userId) {
    return ProfileResponse.from(findByUserId(userId));
  }

  @Transactional
  public ProfileResponse updateMyProfile(Long userId, ProfileUpdateRequest request) {
    if (userProfileRepository.existsByNicknameAndUserIdNot(request.nickname(), userId)) {
      throw new DuplicateException(ErrorCode.NICKNAME_DUPLICATED);
    }
    UserProfile profile = findByUserId(userId);
    profile.update(request.nickname(), request.bio(), request.phoneNumber(),
        request.birthDate(), request.profileImageUrl());
    return ProfileResponse.from(profile);
  }

  @Transactional(readOnly = true)
  public ProfileResponse getProfile(Long userId) {
    return ProfileResponse.from(findByUserId(userId));
  }

  private UserProfile findByUserId(Long userId) {
    return userProfileRepository.findByUserId(userId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.PROFILE_NOT_FOUND));
  }
}
```

> **강의 포인트**
> - **WHY** — `update()`만 호출하면 dirty checking이 트랜잭션 커밋 시점에 UPDATE를 자동 발행한다. 명시적 save 호출 불필요.
> - **PITFALL** — 학생이 자주 묻는 것: "save() 안 부르는데 왜 저장되죠?" → 영속성 컨텍스트가 변경 감지(=dirty checking)하기 때문. 7세션 조회수에서 다시 강조.

### 5-6. ProfileController — `@SessionAttribute`와 requireLogin (15분)

```java
@RestController
@RequestMapping("/api/v1/profiles")
@RequiredArgsConstructor
public class ProfileController {
  private final ProfileService profileService;

  @GetMapping("/me")
  public ProfileResponse getMyProfile(
      @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId) {
    return profileService.getMyProfile(requireLogin(loginUserId));
  }

  @PutMapping("/me")
  public ProfileResponse updateMyProfile(
      @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId,
      @Valid @RequestBody ProfileUpdateRequest request) {
    return profileService.updateMyProfile(requireLogin(loginUserId), request);
  }

  @GetMapping("/{userId}")
  public ProfileResponse getProfile(@PathVariable Long userId) {
    return profileService.getProfile(userId);
  }

  private Long requireLogin(Long loginUserId) {
    if (loginUserId == null) {
      throw new UnauthorizedException(ErrorCode.LOGIN_REQUIRED);
    }
    return loginUserId;
  }
}
```

> **강의 포인트**
> - **WHY** — `@SessionAttribute(required = false)`: 세션에 키가 없을 때 예외 대신 null로 받는다. 그래야 우리가 정의한 `LOGIN_REQUIRED`(401)로 일관 변환할 수 있다.
> - **WHAT** — `required = true`(기본값)면 키 없을 때 `MissingSessionAttributeException`이 발생해 흐름이 깨진다.
> - **HOW** — `requireLogin`이 컨트롤러마다 반복된다. **이 중복이 곧 단계 2 동기다 (HandlerMethodArgumentResolver로 제거할 예정).**
> - **PITFALL** — 학생이 "이거 매번 복붙해야 해요?"라고 묻게 두라. 그 의문이 다음 단계 학습의 동력이다.

**시연:**
```bash
# 로그인 후 cookie.txt 보유 상태
curl -i -b cookie.txt http://localhost:8080/api/v1/profiles/me
# 200 + 프로필
curl -i -b cookie.txt -X PUT http://localhost:8080/api/v1/profiles/me \
  -H "Content-Type: application/json" \
  -d '{"nickname":"새닉네임","bio":"안녕하세요","phoneNumber":"010-1234-5678","birthDate":"1995-05-05","profileImageUrl":null}'
# 200 + 갱신된 프로필
curl -i http://localhost:8080/api/v1/profiles/me
# 401 (쿠키 없이)
```

### 다음 차시 예고
"alice는 자기 프로필을 본다. 이제 게시판을 만들 차례. 그런데 게시판을 아무나 만들 수 있으면 곤란하다. **로그인 했다는 것(인증)**과 **그것을 할 권한이 있다는 것(인가)**은 다르다. 다음 시간에 그 차이를 코드로 만든다."

---

## Session 6. Board 도메인 & ADMIN 인가 (90분)

### 6-1. 인증(Authentication) vs 인가(Authorization) (15분)

| 구분 | 인증 | 인가 |
|------|------|------|
| 의미 | 너는 누구인가 | 너는 무엇을 할 수 있는가 |
| 실패 시 | 401 Unauthorized | 403 Forbidden |
| 우리 코드에서 | `requireLogin()` | `validateAdmin()` |

> **강의 포인트**
> - **WHY** — 401과 403을 섞어 쓰면 클라이언트가 "재로그인 유도" / "권한 안내" 분기를 못 한다.
> - **PITFALL** — HTTP 명세상 401은 "인증되지 않음", 403은 "인증은 됐는데 권한 부족". 한국어 번역(미인가/금지)에 헷갈리지 말고 위 규칙을 외울 것.

### 6-2. Board Entity (10분)

```java
@Entity @Table(name = "boards")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Board extends BaseTimeEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(nullable = false, unique = true, length = 100)
  private String name;
  @Column(length = 255)
  private String description;
  public Board(String name, String description) {
    this.name = name; this.description = description;
  }
  public void update(String name, String description) {
    this.name = name; this.description = description;
  }
}
```

```java
public interface BoardRepository extends JpaRepository<Board, Long> {
  boolean existsByName(String name);
}
```

### 6-3. DTO 3종 (5분)

```java
public record BoardCreateRequest(@NotBlank @Size(max = 100) String name,
                                  @Size(max = 255) String description) {}
public record BoardUpdateRequest(@NotBlank @Size(max = 100) String name,
                                  @Size(max = 255) String description) {}
public record BoardResponse(Long id, String name, String description, LocalDateTime createdAt) {
  public static BoardResponse from(Board b) { ... }
}
```

### 6-4. BoardService — validateAdmin (25분)

```java
@Service @RequiredArgsConstructor
public class BoardService {
  private final BoardRepository boardRepository;
  private final UserRepository userRepository;

  @Transactional(readOnly = true)
  public List<BoardResponse> getBoards() {
    return boardRepository.findAll().stream().map(BoardResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public BoardResponse getBoard(Long id) { return BoardResponse.from(findBoard(id)); }

  @Transactional
  public BoardResponse create(Long loginUserId, BoardCreateRequest request) {
    validateAdmin(loginUserId);
    if (boardRepository.existsByName(request.name()))
      throw new DuplicateException(ErrorCode.DUPLICATE_BOARD_NAME);
    Board board = boardRepository.save(new Board(request.name(), request.description()));
    return BoardResponse.from(board);
  }

  @Transactional
  public BoardResponse update(Long id, Long loginUserId, BoardUpdateRequest request) {
    validateAdmin(loginUserId);
    Board board = findBoard(id);
    if (!board.getName().equals(request.name()) && boardRepository.existsByName(request.name()))
      throw new DuplicateException(ErrorCode.DUPLICATE_BOARD_NAME);
    board.update(request.name(), request.description());
    return BoardResponse.from(board);
  }

  @Transactional
  public void delete(Long id, Long loginUserId) {
    validateAdmin(loginUserId);
    boardRepository.delete(findBoard(id));
  }

  private void validateAdmin(Long userId) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
    if (user.getRole() != Role.ADMIN) {
      throw new ForbiddenException(ErrorCode.ADMIN_ONLY);
    }
  }

  private Board findBoard(Long id) {
    return boardRepository.findById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCode.BOARD_NOT_FOUND));
  }
}
```

> **강의 포인트**
> - **WHY** — Role 검사는 Service에. Controller는 "로그인 했는가"만 확인하고, "그 사람이 ADMIN인가"는 비즈니스 규칙이라 Service 책임.
> - **WHAT** — `update()`에서 "이름이 안 바뀐 경우엔 중복 체크 안 함". 미묘하지만 학생들이 잘 놓치는 케이스.
> - **PITFALL** — Role을 세션에 함께 저장해 캐시하고 싶은 충동 — 단계 1에선 의도적으로 안 한다. "권한 박탈 즉시 반영"이 우선이고, 캐싱은 운영 최적화 단계에서.

### 6-5. BoardController (15분)

```java
@RestController @RequestMapping("/api/v1/boards") @RequiredArgsConstructor
public class BoardController {
  private final BoardService boardService;

  @GetMapping public List<BoardResponse> getBoards() { return boardService.getBoards(); }
  @GetMapping("/{id}") public BoardResponse getBoard(@PathVariable Long id) { ... }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public BoardResponse create(
      @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId,
      @Valid @RequestBody BoardCreateRequest request) {
    return boardService.create(requireLogin(loginUserId), request);
  }

  @PutMapping("/{id}") public BoardResponse update(...) { ... }
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(...) { ... }

  private Long requireLogin(Long loginUserId) {
    if (loginUserId == null) throw new UnauthorizedException(ErrorCode.LOGIN_REQUIRED);
    return loginUserId;
  }
}
```

### 6-6. 시연: ADMIN vs USER 권한 차이 (20분)

```bash
# (1) 비로그인 상태에서 게시판 생성 시도 → 401
curl -i -X POST http://localhost:8080/api/v1/boards \
  -H "Content-Type: application/json" \
  -d '{"name":"자유게시판","description":"자유"}'
# 401 LOGIN_REQUIRED

# (2) USER(alice)로 로그인 후 시도 → 403
curl -c alice.txt -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'
curl -i -b alice.txt -X POST http://localhost:8080/api/v1/boards \
  -H "Content-Type: application/json" \
  -d '{"name":"자유게시판","description":"자유"}'
# 403 ADMIN_ONLY

# (3) ADMIN으로 로그인 후 시도 → 201
curl -c admin.txt -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin1234"}'
curl -i -b admin.txt -X POST http://localhost:8080/api/v1/boards \
  -H "Content-Type: application/json" \
  -d '{"name":"자유게시판","description":"자유롭게 쓰는 곳"}'
# 201 + Board JSON

# (4) 누구나 목록 조회 가능
curl http://localhost:8080/api/v1/boards
# 200 + 배열
```

> **강의 포인트**
> - 401과 403이 다른 코드 + 메시지로 내려가는 것을 직접 보여줘라. 이 차이가 인증 vs 인가다.

### 다음 차시 예고
"게시판은 ADMIN이 만들고, 게시글은 모든 USER가 쓴다. 그런데 alice의 글을 bob이 수정하면? 권한 규칙이 한 단계 더 정교해진다. 또 글이 많아지면 한 번에 다 못 내린다 — 페이징이 필요하다. 다음 시간엔 작성자 인가 + Pageable + 조회수까지."

---

## Session 7. Post 도메인 — 작성자 인가, 페이징, 조회수 (90분)

### 7-1. Post Entity — `@ManyToOne` LAZY 두 개 (20분)

```java
@Entity @Table(name = "posts")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post extends BaseTimeEntity {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "board_id", nullable = false)
  private Board board;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User author;

  @Column(nullable = false, length = 200) private String title;
  @Lob @Column(nullable = false, columnDefinition = "TEXT") private String content;
  @Column(nullable = false) private int viewCount;

  public Post(Board board, User author, String title, String content) {
    this.board = board; this.author = author;
    this.title = title; this.content = content;
    this.viewCount = 0;
  }
  public void update(String title, String content) {
    this.title = title; this.content = content;
  }
  public void increaseViewCount() { this.viewCount++; }
  public boolean isAuthor(Long userId) { return author.getId().equals(userId); }
}
```

> **강의 포인트**
> - **WHY** — `@ManyToOne` 기본값은 EAGER다(역사적 실수). 항상 명시적으로 LAZY로 바꿔라.
> - **WHAT** — `isAuthor(userId)`: `author.getId()`는 LAZY 프록시여도 FK가 PK와 같으므로 **추가 SELECT 없이** 가져온다. (Hibernate가 프록시에 ID는 채워둔다.) 이 디테일이 N+1 회피의 핵심.
> - **HOW** — `@Lob` + `columnDefinition = "TEXT"`: 본문은 길어질 수 있으므로 VARCHAR 대신 TEXT.
> - **PITFALL** — Entity에 setter를 만들지 마라. 의미 있는 메서드 (`update`, `increaseViewCount`)로 변경 의도를 표현. 무분별한 setter는 "언제 어디서 바뀌었는지" 추적을 불가능하게 만든다.

### 7-2. PostRepository — fetch join / @EntityGraph (15분)

```java
public interface PostRepository extends JpaRepository<Post, Long> {
  @EntityGraph(attributePaths = {"board", "author"})
  Page<Post> findByBoardId(Long boardId, Pageable pageable);

  @Query("select p from Post p join fetch p.board join fetch p.author where p.id = :id")
  Optional<Post> findDetailById(@Param("id") Long id);
}
```

> **강의 포인트**
> - **WHY** — 목록 10개를 조회하면 PostListResponse가 `author.getUsername()`을 호출한다. EntityGraph 없으면 SELECT 11번(1 + 10).
> - **WHAT** — `@EntityGraph`는 메서드 이름 쿼리에 fetch 힌트만 추가. `@Query` + `join fetch`는 JPQL을 직접 작성하는 방식. 둘 다 같은 목적을 달성한다.
> - **HOW** — 목록은 `@EntityGraph`(간결), 복잡한 단건 조회는 `@Query`(명확).
> - **PITFALL** — `@EntityGraph`로 `Page` + `Pageable` 조합 시 컬렉션(`@OneToMany`) fetch는 페이징과 충돌한다. ToOne 관계만 안전.

### 7-3. DTO 4종 (5분)

```java
public record PostCreateRequest(@NotBlank @Size(max=200) String title, @NotBlank String content) {}
public record PostUpdateRequest(@NotBlank @Size(max=200) String title, @NotBlank String content) {}
public record PostListResponse(Long id, String title, String authorUsername,
                                int viewCount, LocalDateTime createdAt) {
  public static PostListResponse from(Post post) { ... }
}
public record PostResponse(Long id, Long boardId, String boardName, String authorUsername,
                            String title, String content, int viewCount,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
  public static PostResponse from(Post post) { ... }
}
```

> **강의 포인트**
> - **WHY** — 목록(PostListResponse)에 본문(content)을 넣지 마라. 네트워크 낭비. 화면에 필요한 만큼만.

### 7-4. PostService — 작성자 인가, 조회수, dirty checking (20분)

```java
@Service @RequiredArgsConstructor
public class PostService {
  private final PostRepository postRepository;
  private final BoardRepository boardRepository;
  private final UserRepository userRepository;

  @Transactional(readOnly = true)
  public Page<PostListResponse> getPosts(Long boardId, Pageable pageable) {
    if (!boardRepository.existsById(boardId))
      throw new NotFoundException(ErrorCode.BOARD_NOT_FOUND);
    return postRepository.findByBoardId(boardId, pageable).map(PostListResponse::from);
  }

  @Transactional
  public PostResponse create(Long boardId, Long loginUserId, PostCreateRequest request) {
    Board board = boardRepository.findById(boardId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.BOARD_NOT_FOUND));
    User author = userRepository.findById(loginUserId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
    Post post = postRepository.save(new Post(board, author, request.title(), request.content()));
    return PostResponse.from(post);
  }

  @Transactional   // ⚠ readOnly 아님! dirty checking으로 UPDATE 발생
  public PostResponse getPost(Long id) {
    Post post = findPost(id);
    post.increaseViewCount();
    return PostResponse.from(post);
  }

  @Transactional
  public PostResponse update(Long id, Long loginUserId, PostUpdateRequest request) {
    Post post = findPost(id);
    validateAuthor(post, loginUserId);
    post.update(request.title(), request.content());
    return PostResponse.from(post);
  }

  @Transactional
  public void delete(Long id, Long loginUserId) {
    Post post = findPost(id);
    validateAuthor(post, loginUserId);
    postRepository.delete(post);
  }

  private Post findPost(Long id) {
    return postRepository.findDetailById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCode.POST_NOT_FOUND));
  }

  private void validateAuthor(Post post, Long userId) {
    if (!post.isAuthor(userId))
      throw new ForbiddenException(ErrorCode.POST_ACCESS_DENIED);
  }
}
```

> **강의 포인트**
> - **WHY** — `getPost`는 조회지만 `@Transactional(readOnly = true)`가 아니다. `increaseViewCount()`가 호출되어 dirty checking이 INSERT/UPDATE를 발행해야 하기 때문. **이게 readOnly의 의미를 확실히 가르치는 자리다.**
> - **WHAT** — Entity의 setter 없이 `update`/`increaseViewCount` 같은 의도 명확한 메서드로 상태를 바꾼다. 트랜잭션 커밋 시 변경 감지로 UPDATE.
> - **HOW** — 작성자 검증을 `Post.isAuthor()` 메서드로 캡슐화. Service에서는 의도가 드러나고, FK만 비교하므로 author 추가 로딩 없음.
> - **PITFALL** — 조회수 증가 시 동시성 문제(두 명이 동시에 +1)는 단계 1에선 무시한다. 운영 단계에서 `UPDATE ... SET view_count = view_count + 1`로 바꾸거나 Redis/이벤트로 분리할 영역.

### 7-5. PostController — Pageable + @PageableDefault (15분)

```java
@RestController @RequestMapping("/api/v1") @RequiredArgsConstructor
public class PostController {
  private final PostService postService;

  @GetMapping("/boards/{boardId}/posts")
  public Page<PostListResponse> getPosts(
      @PathVariable Long boardId,
      @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC)
      Pageable pageable) {
    return postService.getPosts(boardId, pageable);
  }

  @PostMapping("/boards/{boardId}/posts")
  @ResponseStatus(HttpStatus.CREATED)
  public PostResponse create(
      @PathVariable Long boardId,
      @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId,
      @Valid @RequestBody PostCreateRequest request) {
    return postService.create(boardId, requireLogin(loginUserId), request);
  }

  @GetMapping("/posts/{id}")
  public PostResponse getPost(@PathVariable Long id) { return postService.getPost(id); }

  @PutMapping("/posts/{id}")
  public PostResponse update(
      @PathVariable Long id,
      @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId,
      @Valid @RequestBody PostUpdateRequest request) {
    return postService.update(id, requireLogin(loginUserId), request);
  }

  @DeleteMapping("/posts/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable Long id,
      @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId) {
    postService.delete(id, requireLogin(loginUserId));
  }

  private Long requireLogin(Long loginUserId) {
    if (loginUserId == null) throw new UnauthorizedException(ErrorCode.LOGIN_REQUIRED);
    return loginUserId;
  }
}
```

> **강의 포인트**
> - **WHY** — Spring Data Web이 `Pageable`을 쿼리 파라미터(`?page=0&size=10&sort=createdAt,desc`)에서 자동으로 채워준다.
> - **WHAT** — `@PageableDefault`로 기본값 보장. 클라이언트가 안 보내도 안전.
> - **HOW** — 강의 단계에선 Page 반환. 응답 구조는 `PagedModel`(2세션 WebConfig 덕에) 안정 JSON.

### 7-6. 시연: 글 작성 → 타인 수정 시도 403 (15분)

```bash
# (1) bob 회원가입 + 로그인
curl -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","email":"bob@example.com","password":"password123","nickname":"밥"}'
curl -c bob.txt -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","password":"password123"}'

# (2) bob이 글 작성 (board id=1)
curl -i -b bob.txt -X POST http://localhost:8080/api/v1/boards/1/posts \
  -H "Content-Type: application/json" \
  -d '{"title":"bob의 글","content":"안녕"}'
# 201 Created, id=1 가정

# (3) 목록 조회 (페이징)
curl 'http://localhost:8080/api/v1/boards/1/posts?page=0&size=5&sort=createdAt,desc'

# (4) 상세 조회 — 조회수 +1
curl http://localhost:8080/api/v1/posts/1
curl http://localhost:8080/api/v1/posts/1   # 다시 → viewCount=2

# (5) alice가 bob의 글 수정 시도 → 403
curl -i -b alice.txt -X PUT http://localhost:8080/api/v1/posts/1 \
  -H "Content-Type: application/json" \
  -d '{"title":"가로채기","content":"ㅋㅋ"}'
# 403 POST_ACCESS_DENIED

# (6) bob 본인이 수정 → 200
curl -i -b bob.txt -X PUT http://localhost:8080/api/v1/posts/1 \
  -H "Content-Type: application/json" \
  -d '{"title":"bob의 글(수정)","content":"수정함"}'
# 200

# (7) 로그아웃 후 글 작성 → 401
curl -b bob.txt -X POST http://localhost:8080/api/v1/auth/logout
curl -i -b bob.txt -X POST http://localhost:8080/api/v1/boards/1/posts \
  -H "Content-Type: application/json" \
  -d '{"title":"x","content":"y"}'
# 401 LOGIN_REQUIRED
```

> **강의 포인트**
> - 이 시연이 본 단계 전체의 핵심. 401 / 403 / 정상 200 / 201 / 204를 한 번에 볼 수 있다.

### 다음 차시 예고
"기능은 완성됐다. 마지막으로 (1) 핵심 시나리오가 깨지지 않게 통합 테스트를 짜고, (2) 코드 정리, (3) 그리고 단계 2 — `@SessionAttribute` 반복을 어떻게 없앨 것인지 예고한다."

---

## Session 8. 테스트 & 시연 통합 & 단계 2 예고 (90분)

### 8-1. 테스트 환경 — H2로 빠르게 (10분)

```yaml
# src/test/resources/application.yaml
spring:
  datasource:
    url: jdbc:h2:mem:board;MODE=MySQL;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    hibernate:
      ddl-auto: create-drop
    open-in-view: false
```

> **강의 포인트**
> - **WHY** — 테스트마다 MySQL 띄우기 비효율. H2 인메모리 + MySQL 호환 모드.
> - **PITFALL** — H2와 MySQL은 100% 동일하지 않다(예약어, 정확한 INDEX 동작). CI에는 Testcontainers로 진짜 MySQL 권장이지만, 본 강의 단계는 H2로 충분.

### 8-2. AuthServiceTest — 비밀번호 해시 / 중복 / 세션 (20분)

```java
@SpringBootTest @Transactional
class AuthServiceTest {
  @Autowired AuthService authService;
  @Autowired UserRepository userRepository;
  @Autowired UserProfileRepository userProfileRepository;
  @Autowired PasswordEncoder passwordEncoder;

  @Test
  void should_createUserAndProfile_whenSignup() {
    SignupRequest req = new SignupRequest("tester1", "tester1@example.com", "password123", "테스터");
    UserResponse response = authService.signup(req);
    assertThat(response.username()).isEqualTo("tester1");
    User user = userRepository.findByUsername("tester1").orElseThrow();
    assertThat(user.getPassword()).isNotEqualTo("password123");           // 평문 저장 금지
    assertThat(passwordEncoder.matches("password123", user.getPassword())).isTrue();
    assertThat(userProfileRepository.findByUserId(user.getId())).isPresent();   // 1:1 동시 생성
  }

  @Test
  void should_storeUserIdInSession_whenLoginSucceeds() {
    UserResponse signedUp = authService.signup(
        new SignupRequest("tester1", "tester1@example.com", "password123", "테스터"));
    AuthController controller = new AuthController(authService);
    MockHttpSession session = new MockHttpSession();
    UserResponse response = controller.login(new LoginRequest("tester1", "password123"), session);
    assertThat(response.id()).isEqualTo(signedUp.id());
    assertThat(session.getAttribute(SessionConst.LOGIN_USER_ID)).isEqualTo(signedUp.id());
  }
  // ... 중복/실패 케이스도 동일 패턴
}
```

> **강의 포인트**
> - **WHY** — `@SpringBootTest`로 컨텍스트 전체. `@Transactional`로 테스트마다 롤백 → 격리.
> - **WHAT** — `MockHttpSession`으로 컨트롤러 단위 테스트에서 세션 상호작용까지 검증.
> - **HOW** — 메서드 이름은 `should_<결과>_when<조건>` 패턴. 한 줄 BDD.

### 8-3. PostServiceTest — 인가, 조회수 (15분)

```java
@SpringBootTest @Transactional
class PostServiceTest {
  // setUp으로 author/other/board를 만들고
  @Test
  void should_throwForbiddenException_whenRequesterIsNotAuthor() {
    PostResponse created = postService.create(board.getId(), author.getId(),
        new PostCreateRequest("제목", "내용"));
    assertThatThrownBy(() -> postService.update(created.id(), other.getId(),
        new PostUpdateRequest("수정 제목", "수정 내용")))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void should_increaseViewCount_whenGetPost() {
    PostResponse created = postService.create(board.getId(), author.getId(),
        new PostCreateRequest("제목", "내용"));
    PostResponse viewed = postService.getPost(created.id());
    assertThat(viewed.viewCount()).isEqualTo(1);
  }
}
```

### 8-4. GlobalExceptionHandlerTest — MockMvc로 HTTP 매핑 검증 (15분)

```java
@SpringBootTest @AutoConfigureMockMvc
class GlobalExceptionHandlerTest {
  @Autowired MockMvc mockMvc;

  @Test
  void should_return404WithErrorResponse_whenPostNotFound() throws Exception {
    mockMvc.perform(get("/api/v1/posts/999999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void should_return400WithFieldErrors_whenSignupRequestInvalid() throws Exception {
    String invalidBody = """
        {"username": "ab", "email": "not-an-email", "password": "123", "nickname": ""}
        """;
    mockMvc.perform(post("/api/v1/auth/signup")
            .contentType(MediaType.APPLICATION_JSON).content(invalidBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.errors").isArray());
  }
}
```

> **강의 포인트**
> - **WHY** — Service 단위 테스트는 Java 예외를 본다. 그런데 학생/QA는 "API가 200을 주는가 400을 주는가"가 궁금하다. MockMvc로 그 매핑까지 검증.

### 8-5. 통합 시연 — 시나리오 완전 재현 (15분)

`docs/lecture/STUDENT.md`의 시연 시나리오를 강사가 처음부터 끝까지 한 번 더 실행한다. 학생들이 자기 PC에서 똑같이 따라할 수 있어야 한다.

### 8-6. 단계 2 예고 — 무엇이 아직 부족한가? (15분)

지금까지 만든 코드의 한계점을 정직하게 보여준다.

1. **`@SessionAttribute` + `requireLogin()` 중복** — Controller마다 반복. → `HandlerMethodArgumentResolver`로 커스텀 `@LoginUserId` 어노테이션을 만들면 한 줄로 해결된다.
2. **Spring Security를 BCrypt만 쓰는 낭비** — `UserDetailsService` + `SecurityFilterChain` + `formLogin`/세션 관리까지 표준 메커니즘에 위임할 수 있다.
3. **세션은 서버 메모리에 산다** — 서버를 2대로 늘리면? Sticky session 또는 Redis 세션 저장. 또는 **stateless(JWT)**로 갈 수도 있다.
4. **권한 박탈 후 즉시 반영 vs 캐시 효율** — Role을 세션/JWT에 캐싱하는 트레이드오프.
5. **조회수 동시성** — 두 사용자가 동시에 GET하면 +1을 잃는다. UPDATE 직접 발행 / 비동기 카운터로 발전.

> **단계 2 학습 로드맵 (다음 과정):**
> 1. `HandlerMethodArgumentResolver` + `@LoginUserId` — 중복 제거
> 2. Spring Security `UserDetailsService` 도입 — 표준 인증 위임
> 3. stateless JWT 전환 — 세션 없는 인증
> 4. 권한 표현 정교화 — Method security(`@PreAuthorize`)
> 5. N+1 / 페이징 / DTO projection 심화 — QueryDSL 도입

---

## 강의 진행 시 자주 나오는 질문

| 질문 | 답변 요약 | 심화 설명 |
|------|---------|---------|
| starter security를 빼면 안 되나요? BCryptPasswordEncoder만 따로 쓰면? | 가능하지만 단계 2에서 다시 추가할 거다. 미리 둔다. | `spring-security-crypto` 단독 의존성도 있지만 강의 흐름상 starter로 통일. |
| `@RestController`인데 왜 `@ResponseBody`가 없죠? | `@RestController` = `@Controller` + `@ResponseBody` | Spring 4부터 합쳐졌다. |
| record는 Lombok과 안 충돌하나요? | 충돌 안 함. record는 불변 데이터, Lombok은 mutable Entity에 주로 사용. | 우리 코드도 DTO=record, Entity=Lombok. |
| 양방향 매핑은 언제 쓰나요? | 정말 필요할 때만. 컬렉션 조회 빈도가 너무 높고 추가 쿼리가 부담일 때. | 양방향이면 mappedBy + 연관관계 편의 메서드 + JSON 순환 차단까지 학습 부담 3중. |
| `open-in-view: false`면 Controller에서 lazy 필드 접근 시 어떻게 되나요? | `LazyInitializationException` 발생. | 그래서 Service에서 미리 fetch 또는 DTO 변환을 끝내고 반환. |
| `@Transactional`을 인터페이스 메서드에 붙여도 되나요? | Spring 6은 인터페이스에도 적용. 하지만 구현체에 붙이는 게 명시적. | 본 강의는 구현 클래스에. |
| `findById`가 `Optional`을 반환해서 매번 `orElseThrow`인데 더 깔끔한 방법 없나요? | 도메인별 `findOrThrow` 메서드를 Repository에 default 메서드로 만들거나 Service의 private 헬퍼로. | 본 강의는 Service의 private 헬퍼로 통일. |
| `@SessionAttribute`와 `HttpSession.getAttribute`의 차이? | 전자는 Spring MVC 바인딩, 후자는 Servlet API 직접 호출. | 전자가 테스트하기 좋고 의도가 드러남. |
| 비로그인 사용자가 글 목록을 보는데 왜 401이 안 나나요? | 글 목록은 의도적으로 비로그인 허용. 인증과 인가는 API마다 따로 결정. | 정책: 읽기=공개, 쓰기=로그인, 관리=ADMIN. |
| Page를 JSON으로 직렬화하니 deprecated 경고가 나옵니다 | WebConfig의 `pageSerializationMode = VIA_DTO` 설정 확인 | Spring Data 3.3+ 변경 사항 |
| DataInitializer가 운영에서 돌까봐 무서워요 | `@Profile("!prod")`로 차단. SPRING_PROFILES_ACTIVE=prod로 띄우면 절대 안 돈다. | 그래도 시드 비밀번호는 환경변수로(`APP_ADMIN_PASSWORD`). |

---

## 트러블슈팅 가이드

| 증상 | 원인 | 해결 |
|------|------|------|
| 앱은 뜨는데 모든 요청 401 | Spring Security 자동 설정이 활성 | `SecurityConfig`로 `permitAll()` 명시 |
| createdAt이 null이라 INSERT 실패 | `@EnableJpaAuditing` 누락 | `JpaAuditingConfig`에 추가 확인 |
| 로그인은 되는데 `/me`가 401 | curl에서 `-c`로 저장한 쿠키를 `-b`로 다시 보내지 않음 | 쿠키 파일 핸들링 확인 |
| 회원가입은 됐는데 password가 평문? | `PasswordEncoder` Bean 누락 or `encode()` 호출 빠짐 | SecurityConfig의 Bean 등록 + signup에서 `encode()` 호출 |
| `LazyInitializationException` | Controller/DTO 변환에서 lazy 필드 접근 + `open-in-view: false` | Service 안에서 fetch join / `@EntityGraph`로 미리 로딩 |
| H2 테스트에서 컬럼명 충돌 | `user`가 예약어 | `@Table(name = "users")` 적용 확인 |
| 게시판 생성 시 항상 500 | 게시판 이름 유니크 위반인데 Service에서 사전 체크 없음 | `existsByName` 체크 또는 DB 예외 핸들러 추가 |
| Page 응답 JSON 구조가 예전과 달라요 | Spring Data 3.3+의 직렬화 모드 변경 | `EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` |
| 닉네임 수정 시 자기 자신과 중복으로 409 | `existsByNickname` 사용 | `existsByNicknameAndUserIdNot`로 자기 자신 제외 |
| `@SessionAttribute(required=true)` 사용 후 비로그인 시 500 | `MissingSessionAttributeException`이 `@RestControllerAdvice`에서 처리 안 됨 | `required=false`로 받고 `requireLogin()`에서 401 변환 |
| MySQL `LOCK WAIT TIMEOUT`이 가끔 발생 | 트랜잭션 안에서 외부 호출이나 너무 긴 작업 | Service 메서드 안에서 외부 IO 금지, 트랜잭션 짧게 |

---

## 한눈에 보는 의존성 순서 다이어그램

```
[Session 1] BoardApplication / build.gradle / application.yaml
        ↓ (실행 환경 준비)
[Session 2] global/
        ├── BaseTimeEntity  ─────────── 모든 Entity의 부모
        ├── ErrorCode, ErrorResponse
        ├── BusinessException 계열  ───── Service에서 던질 예외
        ├── GlobalExceptionHandler ────── 예외→HTTP 매핑
        ├── SecurityConfig ─────────── PasswordEncoder Bean
        ├── WebConfig ────────────── Page 직렬화
        ├── JpaAuditingConfig ──────── @CreatedDate 동작 보장
        └── DataInitializer ─────────── (User/UserProfile 필요해서 5세션에 활성화)
        ↓
[Session 3] user/  ←── BaseTimeEntity
        ├── Role, User
        ├── UserRepository
        └── dto/UserResponse
        ↓
[Session 4] auth/
        ├── SessionConst
        ├── dto/LoginRequest, SignupRequest
        ├── AuthService  ←── UserRepository, PasswordEncoder, (UserProfileRepository)
        └── AuthController ←── HttpSession
        ↓
[Session 5] profile/  ←── User
        ├── UserProfile (@OneToOne LAZY)
        ├── UserProfileRepository (@EntityGraph)
        ├── ProfileService
        ├── ProfileController (@SessionAttribute)
        └── dto/ProfileResponse, ProfileUpdateRequest
        ↓ (이 시점에 DataInitializer 활성화)
[Session 6] board/
        ├── Board
        ├── BoardRepository
        ├── BoardService (validateAdmin) ←── UserRepository (Role 검사)
        ├── BoardController
        └── dto/BoardCreateRequest, BoardUpdateRequest, BoardResponse
        ↓
[Session 7] post/  ←── Board, User
        ├── Post (@ManyToOne LAZY × 2)
        ├── PostRepository (@EntityGraph, @Query join fetch)
        ├── PostService (작성자 인가, 조회수 dirty checking)
        ├── PostController (@PageableDefault)
        └── dto/PostCreateRequest, PostUpdateRequest, PostListResponse, PostResponse
        ↓
[Session 8] test/
        ├── AuthServiceTest (@SpringBootTest @Transactional)
        ├── ProfileServiceTest
        ├── PostServiceTest
        └── GlobalExceptionHandlerTest (@AutoConfigureMockMvc)
```

이 다이어그램의 한 줄 요약: **"아래 레이어가 위 레이어를 모르고, 위 레이어는 아래 레이어를 안다."** 그래서 아래부터 쌓아 올라간다.
