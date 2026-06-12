# 학생용 — Spring Boot 게시판 만들기 [단계 1]

**기술 스택**: Spring Boot 3.5.15, Java 21, Spring Data JPA, Spring Security(BCrypt only), MySQL 8 / 테스트 H2
**선수 지식**: Java 기본 문법, SQL 기초, HTTP 메서드/상태코드 개념
**도구**: JDK 21, Gradle, MySQL 8, IDE(IntelliJ 권장), curl 또는 Postman

## 이 강의가 끝나면 할 수 있는 것

- Spring Boot 프로젝트를 처음부터 띄우고 3계층(Controller / Service / Repository)으로 도메인을 추가할 수 있다.
- JPA 단방향 `@ManyToOne` / `@OneToOne` 매핑을 직접 작성할 수 있다.
- HTTP 세션으로 로그인 상태를 유지하는 흐름을 코드로 구현할 수 있다.
- 인증(누구인가)과 인가(무엇을 할 수 있는가)를 분리해 구현할 수 있다.
- 예외를 `@RestControllerAdvice`로 일괄 처리해 일관된 ErrorResponse를 만들 수 있다.

## 최종 산출물

회원가입 / 로그인 / 로그아웃 / 프로필 관리 / 게시판(ADMIN만 생성) / 게시글(작성자만 수정·삭제) 가 동작하는 REST API.

---

## 학습 순서 — 왜 이 순서인가?

코드는 의존성 방향대로 쌓아야 컴파일·실행이 된다.

```
Step 1. 프로젝트 셋업       (build.gradle, application.yaml)
Step 2. 공통 기반          (BaseTimeEntity, 예외 계층, Security/Web/Auditing 설정)
Step 3. User 도메인        (Entity → Repository → DTO)
Step 4. 회원가입 + 세션 로그인 (AuthService, AuthController, SessionConst)
Step 5. UserProfile (1:1)  (분리 이유, @OneToOne, @SessionAttribute)
Step 6. Board + ADMIN 인가  (Role 검사, 401 vs 403)
Step 7. Post + 작성자 인가  (@ManyToOne LAZY, 페이징, 조회수, N+1)
Step 8. 테스트            (@SpringBootTest, MockMvc)
```

핵심 원칙 한 줄: **"아래 레이어는 위 레이어를 모른다."** Entity는 누가 자기를 쓰는지 모르고, Repository는 Service를 모른다. 그래서 Entity부터 만든다.

---

## Step 1. 프로젝트 셋업

### 1-1. `build.gradle`

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

> **포인트**
> - `starter-web`: Tomcat + Spring MVC + Jackson을 한꺼번에 가져온다.
> - `starter-security`: 인증/인가 필터. 이 단계에선 **BCrypt만** 사용한다.
> - `runtimeOnly`로 mysql-connector를 넣는 이유: JDBC 드라이버는 런타임에만 필요. 컴파일 의존성에 넣을 필요가 없다.

### 1-2. `src/main/resources/application.yaml`

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
      ddl-auto: update
    open-in-view: false
    properties:
      hibernate:
        format_sql: true

logging:
  level:
    org.hibernate.SQL: debug
```

> **포인트**
> - `${DB_PASSWORD:1234}`: 환경변수 우선, 없으면 기본값. 운영에서는 평문 yaml 비밀번호 절대 금지.
> - `ddl-auto: update`: 강의용. 운영은 `validate` + 마이그레이션 도구(Flyway/Liquibase).
> - `open-in-view: false`: lazy 로딩 문제를 일찍 발견할 수 있게 처음부터 끈다.

### 1-3. `BoardApplication`

```java
package com.example.board;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BoardApplication {
  public static void main(String[] args) {
    SpringApplication.run(BoardApplication.class, args);
  }
}
```

### 1-4. 패키지 구조 만들기

```
com.example.board
├── BoardApplication
├── global/
│   ├── config/
│   ├── entity/
│   └── exception/
├── auth/
│   └── dto/
├── user/
│   └── dto/
├── profile/
│   └── dto/
├── board/
│   └── dto/
└── post/
    └── dto/
```

### 실습 1
- MySQL에 `board` 데이터베이스를 만든다 (`CREATE DATABASE board CHARACTER SET utf8mb4;`).
- `./gradlew bootRun`으로 앱이 뜨는지 확인. 콘솔에 "Started BoardApplication"이 나오면 성공.

---

## Step 2. 공통 기반

### 2-1. `BaseTimeEntity`

```java
package com.example.board.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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

### 2-2. `JpaAuditingConfig`

```java
package com.example.board.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {}
```

> **포인트**
> - `@MappedSuperclass`로 공통 컬럼을 상속하면 모든 Entity가 createdAt/updatedAt을 자동으로 갖는다.
> - `@EnableJpaAuditing`이 없으면 createdAt이 null이 되어 INSERT가 실패한다. 가장 흔한 실수.

### 2-3. 예외 계층 — `ErrorCode`

```java
package com.example.board.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
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

  private final HttpStatus status;
  private final String message;
}
```

### 2-4. `BusinessException` + 4개 자식

```java
package com.example.board.global.exception;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
  private final ErrorCode errorCode;
  public BusinessException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }
}
```

```java
public class NotFoundException extends BusinessException {
  public NotFoundException(ErrorCode errorCode) { super(errorCode); }
}
public class DuplicateException extends BusinessException {
  public DuplicateException(ErrorCode errorCode) { super(errorCode); }
}
public class UnauthorizedException extends BusinessException {
  public UnauthorizedException(ErrorCode errorCode) { super(errorCode); }
}
public class ForbiddenException extends BusinessException {
  public ForbiddenException(ErrorCode errorCode) { super(errorCode); }
}
```

### 2-5. `ErrorResponse`

```java
package com.example.board.global.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String code, String message, LocalDateTime timestamp,
    List<FieldErrorDetail> errors
) {
  public record FieldErrorDetail(String field, String reason) {}

  public static ErrorResponse of(ErrorCode errorCode) {
    return new ErrorResponse(errorCode.name(), errorCode.getMessage(), LocalDateTime.now(), null);
  }
  public static ErrorResponse of(ErrorCode errorCode, List<FieldErrorDetail> errors) {
    return new ErrorResponse(errorCode.name(), errorCode.getMessage(), LocalDateTime.now(), errors);
  }
}
```

### 2-6. `GlobalExceptionHandler`

```java
package com.example.board.global.exception;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

> **포인트**
> - Service는 "무엇이 잘못됐는가"(ErrorCode)만 알고 HTTP는 모른다.
> - 변환을 한 곳에 모으면 새 에러 케이스 추가 시 enum 한 줄만 늘리면 된다.
> - 마지막 `Exception.class` 핸들러는 메시지를 응답에 노출하지 않는다(보안).

### 2-7. `SecurityConfig`

```java
package com.example.board.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

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

> **포인트**
> - starter-security를 추가하면 기본으로 **모든 요청이 401**이 된다. 이 설정으로 모두 열어두고, 인증/인가는 우리가 직접 한다.
> - BCrypt는 비밀번호 해시 표준. 직접 구현하면 안 되는 영역이라 라이브러리를 빌려쓴다.
> - `csrf.disable()`은 JSON API + 학습용. 쿠키 기반 폼 운영에서는 절대 끄지 않는다.

### 2-8. `WebConfig`

```java
package com.example.board.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;

@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
public class WebConfig {}
```

> **포인트**
> - Spring Data 3.3+에서 `Page` 직접 직렬화는 deprecated. `PagedModel`로 안정된 JSON 구조 보장.

### 실습 2
- 위 파일을 모두 만든 뒤 다시 `./gradlew bootRun`. 여전히 정상 기동되어야 한다.
- `curl -i http://localhost:8080/api/v1/posts/999999` → 404가 나와야 한다. (다음 Step에서 컨트롤러 만들면 확인 가능)

---

## Step 3. User 도메인

### 3-1. `Role`

```java
package com.example.board.user;
public enum Role { USER, ADMIN }
```

### 3-2. `User` Entity

```java
package com.example.board.user;

import com.example.board.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
  private String password;     // BCrypt 해시

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Role role;

  public User(String username, String email, String password, Role role) {
    this.username = username;
    this.email = email;
    this.password = password;
    this.role = role;
  }
}
```

> **포인트**
> - `@NoArgsConstructor(access = PROTECTED)`: JPA만 쓰는 빈 생성자. 외부 코드는 못 만든다.
> - `@Enumerated(EnumType.STRING)` 필수. `ORDINAL`은 enum 순서 바뀌면 DB가 무너진다.
> - `@Table(name = "users")`: MySQL/PostgreSQL에서 `user`는 예약어.
> - 연관관계 없음 — 단방향만 사용한다.

### 3-3. `UserRepository`

```java
package com.example.board.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
  Optional<User> findByUsername(String username);
  boolean existsByUsername(String username);
  boolean existsByEmail(String email);
}
```

### 3-4. `UserResponse` DTO

```java
package com.example.board.user.dto;

import com.example.board.user.User;

public record UserResponse(Long id, String username, String email, String role) {
  public static UserResponse from(User user) {
    return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole().name());
  }
}
```

> **포인트**
> - Entity를 직접 반환하면 password 해시까지 응답에 나간다. DTO로 변환해 노출 필드를 통제하라.
> - record는 불변. DTO에 완벽한 도구.

---

## Step 4. 회원가입 + HTTP 세션 로그인

### 4-1. `SignupRequest`, `LoginRequest`, `SessionConst`

```java
package com.example.board.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
    @NotBlank @Size(min = 4, max = 50) String username,
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8) String password,
    @NotBlank @Size(max = 50) String nickname
) {}
```

```java
package com.example.board.auth.dto;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
```

```java
package com.example.board.auth;

public final class SessionConst {
  public static final String LOGIN_USER_ID = "loginUserId";
  private SessionConst() {}
}
```

### 4-2. `AuthService`

```java
package com.example.board.auth;

import com.example.board.auth.dto.LoginRequest;
import com.example.board.auth.dto.SignupRequest;
import com.example.board.global.exception.*;
import com.example.board.profile.UserProfile;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import com.example.board.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
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
        request.username(),
        request.email(),
        passwordEncoder.encode(request.password()),
        Role.USER));
    userProfileRepository.save(new UserProfile(user, request.nickname(), null));
    return UserResponse.from(user);
  }

  @Transactional(readOnly = true)
  public UserResponse login(LoginRequest request) {
    User user = userRepository.findByUsername(request.username())
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.LOGIN_FAILED));
    if (!passwordEncoder.matches(request.password(), user.getPassword())) {
      throw new UnauthorizedException(ErrorCode.LOGIN_FAILED);
    }
    return UserResponse.from(user);
  }
}
```

> **포인트**
> - `@Transactional`이 메서드 시작에 트랜잭션을 연다. User만 저장되고 Profile이 실패하면 둘 다 롤백 → 1:1 무결성 보장.
> - `passwordEncoder.encode()`는 매번 다른 결과를 낸다 (salt 자동 포함).
> - 로그인 실패 시 username 미존재와 password 불일치를 **같은 메시지**로 응답 → user enumeration 공격 방지.

### 4-3. `AuthController`

```java
package com.example.board.auth;

import com.example.board.auth.dto.LoginRequest;
import com.example.board.auth.dto.SignupRequest;
import com.example.board.user.dto.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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

  @PostMapping("/login")
  public UserResponse login(@Valid @RequestBody LoginRequest request, HttpSession session) {
    UserResponse user = authService.login(request);
    session.setAttribute(SessionConst.LOGIN_USER_ID, user.id());
    return user;
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) session.invalidate();
  }
}
```

> **포인트 (HTTP 세션 흐름)**
> 1. 로그인 성공 → 서버가 세션을 만들고 userId 저장.
> 2. 응답 헤더 `Set-Cookie: JSESSIONID=...`로 세션 ID를 클라이언트에 전달.
> 3. 이후 요청은 쿠키로 JSESSIONID를 보내면 서버가 세션을 찾아 사용자를 식별.
> 4. 로그아웃 = `session.invalidate()` → 서버 측 세션 데이터 제거.

### 실습 3 — curl로 직접 해보기

```bash
# (1) 회원가입
curl -i -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"password123","nickname":"앨리스"}'
# 201 Created

# (2) MySQL에서 password 컬럼 확인 — $2a$10$... 형태여야 함
# select id, username, password from users;

# (3) 검증 실패 케이스
curl -i -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"username":"a","email":"bad","password":"1","nickname":""}'
# 400 + errors 배열

# (4) 로그인 + 쿠키 저장
curl -i -c cookie.txt -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'
# Set-Cookie: JSESSIONID=... 확인

# (5) 로그인 실패
curl -i -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"wrong"}'
# 401 LOGIN_FAILED

# (6) 로그아웃
curl -i -b cookie.txt -X POST http://localhost:8080/api/v1/auth/logout
# 204
```

---

## Step 5. UserProfile (1:1) & 내 프로필 API

### 왜 User와 UserProfile을 분리하나?
- 인증 데이터(password, role)는 거의 안 바뀐다. 프로필은 자주 바뀐다.
- 인증 시 password가 노출되는 경로와 프로필 노출 경로를 분리.
- 닉네임/아바타/자기소개 같은 화면용 필드는 user 테이블을 부풀리지 않고 profile에 모은다.

### 5-1. `UserProfile` Entity

```java
package com.example.board.profile;

import com.example.board.global.entity.BaseTimeEntity;
import com.example.board.user.User;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
    this.user = user;
    this.nickname = nickname;
    this.bio = bio;
  }

  public void update(String nickname, String bio, String phoneNumber,
                     LocalDate birthDate, String profileImageUrl) {
    this.nickname = nickname;
    this.bio = bio;
    this.phoneNumber = phoneNumber;
    this.birthDate = birthDate;
    this.profileImageUrl = profileImageUrl;
  }
}
```

> **포인트**
> - **단방향**: UserProfile만 User를 안다. User는 자기 프로필을 모른다.
> - `@JoinColumn(unique = true)`: FK에 UNIQUE → 1:1 강제.
> - LAZY로 두고, 필요할 때 EntityGraph로 조인.

### 5-2. `UserProfileRepository`

```java
package com.example.board.profile;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
  @EntityGraph(attributePaths = "user")
  Optional<UserProfile> findByUserId(Long userId);

  boolean existsByNickname(String nickname);
  boolean existsByNicknameAndUserIdNot(String nickname, Long userId);
}
```

> **포인트**
> - `@EntityGraph`로 한 쿼리에 User까지 함께 fetch → N+1 방지.
> - `existsByNicknameAndUserIdNot`: 자기 자신은 제외하고 닉네임 중복 검사 (수정 시 닉네임 안 바꿔도 통과).

### 5-3. DTO

```java
package com.example.board.profile.dto;

import com.example.board.profile.UserProfile;
import java.time.LocalDate;

public record ProfileResponse(
    Long userId, String username, String email, String nickname,
    String bio, String phoneNumber, LocalDate birthDate, String profileImageUrl
) {
  public static ProfileResponse from(UserProfile profile) {
    return new ProfileResponse(
        profile.getUser().getId(),
        profile.getUser().getUsername(),
        profile.getUser().getEmail(),
        profile.getNickname(),
        profile.getBio(),
        profile.getPhoneNumber(),
        profile.getBirthDate(),
        profile.getProfileImageUrl());
  }
}
```

```java
package com.example.board.profile.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record ProfileUpdateRequest(
    @NotBlank @Size(max = 50) String nickname,
    @Size(max = 500) String bio,
    @Pattern(regexp = "^01[0-9]-\\d{3,4}-\\d{4}$") String phoneNumber,
    @Past LocalDate birthDate,
    @Size(max = 500) String profileImageUrl
) {}
```

> **포인트**
> - `@Pattern`은 **null이면 통과**한다. 선택 입력 필드의 검증 의도를 자연스럽게 표현.

### 5-4. `ProfileService`

```java
package com.example.board.profile;

import com.example.board.global.exception.*;
import com.example.board.profile.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
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

> **포인트**
> - `profile.update()`만 호출하고 save()를 안 쓴다. 트랜잭션 커밋 시 변경 감지(dirty checking)로 UPDATE 자동 발행.

### 5-5. `ProfileController`

```java
package com.example.board.profile;

import com.example.board.auth.SessionConst;
import com.example.board.global.exception.*;
import com.example.board.profile.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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

> **포인트**
> - `@SessionAttribute(required = false)`: 세션 키가 없으면 예외 대신 null → `requireLogin()`이 401로 통일.
> - 이 `requireLogin` 헬퍼가 Controller마다 반복된다. **다음 단계에서 이 중복을 없앤다.**

### 5-6. `DataInitializer` (ADMIN 시드)

이제 UserProfile이 생겼으니 활성화한다.

```java
package com.example.board.global.config;

import com.example.board.profile.UserProfile;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Profile("!prod")
@RequiredArgsConstructor
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

### 실습 4

```bash
# 로그인 후 쿠키 저장
curl -c alice.txt -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'

# 내 프로필 조회
curl -i -b alice.txt http://localhost:8080/api/v1/profiles/me
# 200 + 프로필

# 내 프로필 수정
curl -i -b alice.txt -X PUT http://localhost:8080/api/v1/profiles/me \
  -H "Content-Type: application/json" \
  -d '{"nickname":"새닉네임","bio":"안녕하세요","phoneNumber":"010-1234-5678","birthDate":"1995-05-05","profileImageUrl":null}'
# 200

# 비로그인 상태에서 내 프로필 → 401
curl -i http://localhost:8080/api/v1/profiles/me
# 401 LOGIN_REQUIRED
```

---

## Step 6. Board + ADMIN 인가

### 6-1. 인증 vs 인가

| 구분 | 인증(Authentication) | 인가(Authorization) |
|------|-------|------|
| 의미 | 너는 누구인가 | 너는 무엇을 할 수 있는가 |
| 실패 시 HTTP | 401 Unauthorized | 403 Forbidden |
| 우리 코드 | `requireLogin()` | `validateAdmin()` |

### 6-2. `Board`, Repository, DTO

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

```java
public record BoardCreateRequest(@NotBlank @Size(max = 100) String name,
                                  @Size(max = 255) String description) {}
public record BoardUpdateRequest(@NotBlank @Size(max = 100) String name,
                                  @Size(max = 255) String description) {}
public record BoardResponse(Long id, String name, String description, LocalDateTime createdAt) {
  public static BoardResponse from(Board b) {
    return new BoardResponse(b.getId(), b.getName(), b.getDescription(), b.getCreatedAt());
  }
}
```

### 6-3. `BoardService`

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

> **포인트**
> - Role 검사는 Service에서. Controller는 "로그인 했는가"만 본다.
> - 이름이 안 바뀐 경우엔 중복 검사 skip — 흔히 놓치는 케이스.

### 6-4. `BoardController`

```java
@RestController @RequestMapping("/api/v1/boards") @RequiredArgsConstructor
public class BoardController {
  private final BoardService boardService;

  @GetMapping
  public List<BoardResponse> getBoards() { return boardService.getBoards(); }

  @GetMapping("/{id}")
  public BoardResponse getBoard(@PathVariable Long id) { return boardService.getBoard(id); }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public BoardResponse create(
      @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId,
      @Valid @RequestBody BoardCreateRequest request) {
    return boardService.create(requireLogin(loginUserId), request);
  }

  @PutMapping("/{id}")
  public BoardResponse update(
      @PathVariable Long id,
      @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId,
      @Valid @RequestBody BoardUpdateRequest request) {
    return boardService.update(id, requireLogin(loginUserId), request);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @PathVariable Long id,
      @SessionAttribute(name = SessionConst.LOGIN_USER_ID, required = false) Long loginUserId) {
    boardService.delete(id, requireLogin(loginUserId));
  }

  private Long requireLogin(Long loginUserId) {
    if (loginUserId == null) throw new UnauthorizedException(ErrorCode.LOGIN_REQUIRED);
    return loginUserId;
  }
}
```

### 실습 5

```bash
# (1) 비로그인 게시판 생성 → 401
curl -i -X POST http://localhost:8080/api/v1/boards \
  -H "Content-Type: application/json" \
  -d '{"name":"자유게시판","description":"자유"}'

# (2) USER(alice)로 시도 → 403
curl -i -b alice.txt -X POST http://localhost:8080/api/v1/boards \
  -H "Content-Type: application/json" \
  -d '{"name":"자유게시판","description":"자유"}'

# (3) ADMIN으로 → 201
curl -c admin.txt -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin1234"}'
curl -i -b admin.txt -X POST http://localhost:8080/api/v1/boards \
  -H "Content-Type: application/json" \
  -d '{"name":"자유게시판","description":"자유롭게"}'

# (4) 누구나 목록 조회
curl http://localhost:8080/api/v1/boards
```

---

## Step 7. Post + 작성자 인가, 페이징, 조회수

### 7-1. `Post` Entity

```java
package com.example.board.post;

import com.example.board.board.Board;
import com.example.board.global.entity.BaseTimeEntity;
import com.example.board.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "posts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

  // FK(user_id)만 비교 → author 추가 SELECT 없음
  public boolean isAuthor(Long userId) {
    return author.getId().equals(userId);
  }
}
```

> **포인트**
> - `@ManyToOne` 기본값은 EAGER(역사적 실수). 항상 **명시적으로 LAZY**.
> - `isAuthor(userId)`: LAZY 프록시여도 FK는 메모리에 이미 있어 추가 쿼리 없이 비교 가능.
> - Entity setter 금지. 의미 있는 메서드(`update`, `increaseViewCount`)로 변경 의도 표현.

### 7-2. `PostRepository`

```java
package com.example.board.post;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

  @EntityGraph(attributePaths = {"board", "author"})
  Page<Post> findByBoardId(Long boardId, Pageable pageable);

  @Query("select p from Post p join fetch p.board join fetch p.author where p.id = :id")
  Optional<Post> findDetailById(@Param("id") Long id);
}
```

> **포인트**
> - 목록 10개 조회 시 EntityGraph 없으면 SELECT 1 + 10번 발생(N+1). EntityGraph가 JOIN으로 한 번에.
> - `@EntityGraph`는 메서드 이름 쿼리 + fetch 힌트. `@Query` join fetch는 JPQL 직접 작성. 둘 다 같은 목적.

### 7-3. DTO 4종

```java
public record PostCreateRequest(@NotBlank @Size(max=200) String title, @NotBlank String content) {}
public record PostUpdateRequest(@NotBlank @Size(max=200) String title, @NotBlank String content) {}

public record PostListResponse(Long id, String title, String authorUsername,
                                int viewCount, LocalDateTime createdAt) {
  public static PostListResponse from(Post post) {
    return new PostListResponse(post.getId(), post.getTitle(),
        post.getAuthor().getUsername(), post.getViewCount(), post.getCreatedAt());
  }
}

public record PostResponse(Long id, Long boardId, String boardName, String authorUsername,
                            String title, String content, int viewCount,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
  public static PostResponse from(Post post) {
    return new PostResponse(post.getId(), post.getBoard().getId(), post.getBoard().getName(),
        post.getAuthor().getUsername(), post.getTitle(), post.getContent(),
        post.getViewCount(), post.getCreatedAt(), post.getUpdatedAt());
  }
}
```

> **포인트**
> - 목록 DTO에 content 넣지 마라. 네트워크 낭비.

### 7-4. `PostService`

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

  @Transactional    // readOnly 아님! dirty checking으로 viewCount UPDATE
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

> **포인트**
> - `getPost`가 `@Transactional(readOnly = true)`가 **아닌** 이유: `increaseViewCount()`로 상태가 바뀌고 dirty checking이 UPDATE를 발행해야 한다.
> - 작성자 검증을 `Post.isAuthor()` 메서드로 캡슐화 — 의도가 드러나고 FK만 비교라 LAZY 추가 로딩 없음.

### 7-5. `PostController`

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

> **포인트**
> - `Pageable`은 쿼리 파라미터로 자동 채워진다(`?page=0&size=10&sort=createdAt,desc`).
> - `@PageableDefault`로 클라이언트가 안 보내도 기본값 보장.

### 실습 6 — 전체 시연 시나리오

이 시나리오를 끝까지 실행할 수 있으면 강의의 목표를 달성한 것이다.

```bash
# (1) bob 회원가입
curl -X POST http://localhost:8080/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","email":"bob@example.com","password":"password123","nickname":"밥"}'

# (2) alice / bob 로그인
curl -c alice.txt -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'
curl -c bob.txt -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","password":"password123"}'

# (3) ADMIN으로 게시판 생성 (id=1로 가정)
curl -c admin.txt -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin1234"}'
curl -b admin.txt -X POST http://localhost:8080/api/v1/boards \
  -H "Content-Type: application/json" \
  -d '{"name":"자유게시판","description":"자유롭게"}'

# (4) bob이 글 작성
curl -i -b bob.txt -X POST http://localhost:8080/api/v1/boards/1/posts \
  -H "Content-Type: application/json" \
  -d '{"title":"bob의 글","content":"안녕"}'
# 201 + id=1 가정

# (5) 페이징 목록
curl 'http://localhost:8080/api/v1/boards/1/posts?page=0&size=5&sort=createdAt,desc'

# (6) 상세 조회 (조회수 +1)
curl http://localhost:8080/api/v1/posts/1
curl http://localhost:8080/api/v1/posts/1   # 다시 → viewCount=2

# (7) alice가 bob 글 수정 시도 → 403
curl -i -b alice.txt -X PUT http://localhost:8080/api/v1/posts/1 \
  -H "Content-Type: application/json" \
  -d '{"title":"가로채기","content":"ㅋ"}'

# (8) bob 본인 수정 → 200
curl -i -b bob.txt -X PUT http://localhost:8080/api/v1/posts/1 \
  -H "Content-Type: application/json" \
  -d '{"title":"bob의 글(수정)","content":"수정함"}'

# (9) 로그아웃 후 글 작성 시도 → 401
curl -b bob.txt -X POST http://localhost:8080/api/v1/auth/logout
curl -i -b bob.txt -X POST http://localhost:8080/api/v1/boards/1/posts \
  -H "Content-Type: application/json" \
  -d '{"title":"x","content":"y"}'
```

---

## Step 8. 테스트

### 8-1. `src/test/resources/application.yaml` (H2)

```yaml
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

### 8-2. 통합 테스트 예시

```java
@SpringBootTest @Transactional
class AuthServiceTest {

  @Autowired AuthService authService;
  @Autowired UserRepository userRepository;
  @Autowired UserProfileRepository userProfileRepository;
  @Autowired PasswordEncoder passwordEncoder;

  @Test
  void should_createUserAndProfile_whenSignup() {
    SignupRequest request =
        new SignupRequest("tester1", "tester1@example.com", "password123", "테스터");

    UserResponse response = authService.signup(request);

    assertThat(response.username()).isEqualTo("tester1");
    User user = userRepository.findByUsername("tester1").orElseThrow();
    assertThat(user.getPassword()).isNotEqualTo("password123");    // 평문 저장 금지
    assertThat(passwordEncoder.matches("password123", user.getPassword())).isTrue();
    assertThat(userProfileRepository.findByUserId(user.getId())).isPresent();
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
}
```

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
}
```

> **포인트**
> - `@Transactional` on 테스트 클래스 → 각 테스트 끝나면 자동 롤백 → 격리.
> - `@SpringBootTest`는 전체 컨텍스트를 띄운다. Service/Repository/예외 핸들러까지 진짜로 동작.
> - `MockMvc`로 HTTP 상태 코드까지 검증 가능.

---

## 다음 단계 예고 (단계 2)

지금 코드의 한계점을 의도적으로 남겨뒀다. 다음 과정에서 하나씩 개선한다.

1. **`@SessionAttribute` + `requireLogin()` 중복** → `HandlerMethodArgumentResolver`로 커스텀 `@LoginUserId` 어노테이션을 만들어 한 줄로 줄인다.
2. **Spring Security 미활용** → `UserDetailsService` + `SecurityFilterChain`으로 표준 인증 위임.
3. **세션이 서버 메모리에만 존재** → 서버 2대로 늘리면? Redis 세션 저장 또는 **stateless(JWT)** 전환.
4. **권한 박탈 즉시 반영 vs 캐싱** — Role 캐싱 전략.
5. **조회수 동시성** — 두 명이 동시에 조회하면 +1이 사라진다. UPDATE 직접 발행 / 비동기 카운터로 개선.

---

## 자주 묻는 질문

| 질문 | 답변 |
|------|------|
| `@RestController`인데 왜 `@ResponseBody`가 없죠? | `@RestController` = `@Controller` + `@ResponseBody` |
| record는 Lombok과 충돌하지 않나요? | 충돌 안 함. record는 불변 DTO, Lombok은 mutable Entity 주로. |
| `findById`가 `Optional`을 반환해서 매번 `orElseThrow`인데 더 깔끔한 방법은? | Service의 private 헬퍼로 통일. |
| 비로그인 사용자가 글 목록을 보는데 왜 401이 안 나나요? | 글 목록은 의도적으로 비로그인 허용. 정책: 읽기=공개, 쓰기=로그인, 관리=ADMIN. |
| Page를 JSON으로 직렬화하니 deprecated 경고가 나옵니다 | WebConfig의 `pageSerializationMode = VIA_DTO` 설정 확인. |
| 양방향 매핑은 언제 쓰나요? | 정말 필요할 때만. 본 강의는 단방향으로 통일. |
| `open-in-view: false`로 두면 Controller에서 lazy 접근 시? | `LazyInitializationException` 발생. Service에서 미리 fetch / DTO로 변환 후 반환. |

## 흔한 에러 빠른 진단

| 증상 | 해결 |
|------|------|
| 모든 요청에 401 | SecurityConfig 누락 또는 `permitAll()` 빠짐 |
| createdAt이 null이라 INSERT 실패 | `@EnableJpaAuditing` 누락 |
| 로그인은 되는데 `/me`가 401 | curl `-c`로 저장한 쿠키를 `-b`로 보내지 않음 |
| password가 평문으로 저장 | `passwordEncoder.encode()` 호출 빠짐 |
| 닉네임 수정 시 자기 자신과 중복으로 409 | `existsByNickname` 대신 `existsByNicknameAndUserIdNot` 사용 |
| `LazyInitializationException` | `@EntityGraph` / `join fetch`로 미리 로딩 |

---

## 학습 체크리스트

스스로 점검해보자. 모두 ✅ 되면 단계 1 완료.

- [ ] 3계층(Controller / Service / Repository)을 왜 나누는지 설명할 수 있다.
- [ ] BCryptPasswordEncoder로 비밀번호를 저장하는 흐름을 코드로 작성할 수 있다.
- [ ] HTTP는 stateless인데 어떻게 로그인 상태를 유지하는지 설명할 수 있다.
- [ ] JSESSIONID 쿠키와 서버 세션의 관계를 그림으로 설명할 수 있다.
- [ ] `@SessionAttribute(required = false)`를 쓰는 이유를 안다.
- [ ] 401과 403의 차이를 명확히 안다.
- [ ] `@ManyToOne` 기본 fetch가 EAGER인 함정을 안다.
- [ ] N+1 문제를 SQL 로그로 직접 확인하고 `@EntityGraph`로 해결할 수 있다.
- [ ] `@Transactional`이 dirty checking으로 UPDATE를 자동 발행한다는 것을 안다.
- [ ] `getPost()`가 왜 readOnly가 아닌지 설명할 수 있다.
- [ ] `@RestControllerAdvice`로 예외→HTTP 상태 변환이 어디서 일어나는지 안다.
- [ ] Entity를 직접 응답하지 않고 DTO로 변환하는 이유를 안다.
