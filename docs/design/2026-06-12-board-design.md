# 게시판(Board) 프로젝트 설계 문서

**작성일:** 2026-06-12
**범위:** 신규 기능 — auth, user-profile, board, post 도메인 (강의용)
**기술 스택:** Spring Boot 3.5.15, Java 21, Spring Data JPA, Spring Security, MySQL

---

## DB 스키마

### 테이블: users
| 컬럼명 | 타입 | 제약조건 | 설명 |
|--------|------|----------|------|
| id | BIGINT | PK, AUTO_INCREMENT | |
| username | VARCHAR(50) | NOT NULL, UNIQUE | 로그인 ID |
| email | VARCHAR(100) | NOT NULL, UNIQUE | |
| password | VARCHAR(255) | NOT NULL | BCrypt 해시 저장 |
| role | VARCHAR(20) | NOT NULL | USER / ADMIN |
| created_at | DATETIME | NOT NULL | |
| updated_at | DATETIME | NOT NULL | |

### 테이블: user_profiles
| 컬럼명 | 타입 | 제약조건 | 설명 |
|--------|------|----------|------|
| id | BIGINT | PK, AUTO_INCREMENT | |
| user_id | BIGINT | FK(users.id), NOT NULL, UNIQUE | 1:1 관계 |
| nickname | VARCHAR(50) | NOT NULL, UNIQUE | 닉네임 중복 금지 |
| bio | VARCHAR(500) | NULL | 자기소개 |
| phone_number | VARCHAR(20) | NULL | 선택 입력 |
| birth_date | DATE | NULL | 선택 입력 |
| profile_image_url | VARCHAR(500) | NULL | 선택 입력 |
| created_at | DATETIME | NOT NULL | |
| updated_at | DATETIME | NOT NULL | |

> 인증 정보(users)와 부가 정보(user_profiles)를 분리하면 보안 민감 데이터와
> 자주 바뀌는 데이터의 관심사가 나뉜다 (강의 포인트).

### 테이블: boards
| 컬럼명 | 타입 | 제약조건 | 설명 |
|--------|------|----------|------|
| id | BIGINT | PK, AUTO_INCREMENT | |
| name | VARCHAR(100) | NOT NULL, UNIQUE | 게시판 이름 (예: 자유게시판) |
| description | VARCHAR(255) | NULL | |
| created_at | DATETIME | NOT NULL | |
| updated_at | DATETIME | NOT NULL | |

### 테이블: posts
| 컬럼명 | 타입 | 제약조건 | 설명 |
|--------|------|----------|------|
| id | BIGINT | PK, AUTO_INCREMENT | |
| board_id | BIGINT | FK(boards.id), NOT NULL | 소속 게시판 |
| user_id | BIGINT | FK(users.id), NOT NULL | 작성자 |
| title | VARCHAR(200) | NOT NULL | |
| content | TEXT | NOT NULL | |
| view_count | INT | NOT NULL, DEFAULT 0 | |
| created_at | DATETIME | NOT NULL | |
| updated_at | DATETIME | NOT NULL | |

### 관계
- users (1) → user_profiles (1): user_id FK + UNIQUE
- boards (1) → posts (N): board_id FK
- users (1) → posts (N): user_id FK

---

## Entity 및 JPA 관계

### User
- 필드: id, username, email, password, role(Enum), createdAt, updatedAt
- 연관관계 없음 (단방향 설계 — 강의용 단순화를 위해 양방향 컬렉션 생략)

### UserProfile
- `@OneToOne(fetch = LAZY) @JoinColumn(name = "user_id", unique = true)` User user

### Board
- 필드: id, name, description, createdAt, updatedAt
- 연관관계 없음 (Post 쪽에서 단방향으로 참조)

### Post
- `@ManyToOne(fetch = LAZY) @JoinColumn(name = "board_id")` Board board
- `@ManyToOne(fetch = LAZY) @JoinColumn(name = "user_id")` User author

### 관계 설계 결정사항
| 관계 | 전략 | 이유 |
|------|------|------|
| UserProfile → User | @OneToOne LAZY (단방향) | 프로필 조회 시에만 필요 |
| Post → Board | @ManyToOne LAZY (단방향) | 목록 조회 시 N+1 방지, fetch join 사용 |
| Post → User | @ManyToOne LAZY (단방향) | 동일 |

> **단방향만 사용하는 이유 (강의 포인트):** 양방향 관계는 연관관계 주인, 편의 메서드,
> 무한 순환 참조 등 부수 개념이 많아 입문 단계에서 혼란을 준다.
> FK를 가진 쪽에서 단방향 `@ManyToOne`/`@OneToOne`만 사용하고,
> 목록이 필요하면 Repository 쿼리로 해결한다.

- 공통 시각 필드는 `BaseTimeEntity`(`@MappedSuperclass` + JPA Auditing)로 상속
- cascade, orphanRemoval 미사용 (모든 도메인이 독립적)

---

## REST API 명세

### 기본 규칙
- Base URL: `/api/v1`
- 응답 형식: JSON
- 인증: 수동 HttpSession 방식 — 로그인 성공 시 `session.setAttribute("loginUserId", userId)`,
  이후 요청은 JSESSIONID 쿠키로 세션을 식별하고 컨트롤러에서 `@SessionAttribute`로 userId를 꺼낸다.
  Spring Security는 BCrypt(PasswordEncoder)만 사용하고 모든 요청은 `permitAll()`.
  (단계 1: HttpSession 직접 사용 → 단계 2: stateless(JWT) 전환 예정)
- 인증 필요 API: 내 프로필 조회/수정, 게시글 작성/수정/삭제, 게시판 생성/수정/삭제(ADMIN)
- 비로그인 접근 시 401(LOGIN_REQUIRED), 권한 부족 시 403(ADMIN_ONLY / POST_ACCESS_DENIED)

### Auth
| 메서드 | 경로 | 설명 | 요청 바디 | 응답 |
|--------|------|------|-----------|------|
| POST | /api/v1/auth/signup | 회원가입 | SignupRequest | 201, UserResponse |
| POST | /api/v1/auth/login | 로그인 (세션 발급) | LoginRequest | 200, UserResponse |
| POST | /api/v1/auth/logout | 로그아웃 | - | 204 |

#### SignupRequest
| 필드 | 타입 | 필수 | 검증 |
|------|------|------|------|
| username | String | Y | NotBlank, 4~50자 |
| email | String | Y | Email 형식 |
| password | String | Y | NotBlank, 8자 이상 |
| nickname | String | Y | NotBlank, max 50 (프로필 동시 생성), 중복 시 409(NICKNAME_DUPLICATED) |

### UserProfile
| 메서드 | 경로 | 설명 | 요청 바디 | 응답 |
|--------|------|------|-----------|------|
| GET | /api/v1/profiles/me | 내 프로필 조회 | - | ProfileResponse |
| PUT | /api/v1/profiles/me | 내 프로필 수정 | ProfileUpdateRequest | ProfileResponse |
| GET | /api/v1/profiles/{userId} | 타인 프로필 조회 | - | ProfileResponse |

#### ProfileUpdateRequest
| 필드 | 타입 | 필수 | 검증 |
|------|------|------|------|
| nickname | String | Y | NotBlank, max 50, 타인과 중복 시 409(NICKNAME_DUPLICATED) |
| bio | String | N | max 500 |
| phoneNumber | String | N | `^01[0-9]-\d{3,4}-\d{4}$` (@Pattern은 null 통과) |
| birthDate | LocalDate | N | @Past |
| profileImageUrl | String | N | max 500 |

#### ProfileResponse
| 필드 | 타입 | 설명 |
|------|------|------|
| userId | Long | |
| username | String | User에서 조회 (fetch join으로 N+1 방지) |
| email | String | User에서 조회 |
| nickname | String | |
| bio | String | |
| phoneNumber | String | |
| birthDate | LocalDate | |
| profileImageUrl | String | |

### Board
| 메서드 | 경로 | 설명 | 요청 바디 | 응답 |
|--------|------|------|-----------|------|
| GET | /api/v1/boards | 전체 게시판 목록 | - | List\<BoardResponse\> |
| GET | /api/v1/boards/{id} | 게시판 단건 조회 | - | BoardResponse |
| POST | /api/v1/boards | 게시판 생성 (ADMIN) | BoardCreateRequest | 201, BoardResponse |
| PUT | /api/v1/boards/{id} | 게시판 수정 (ADMIN) | BoardUpdateRequest | BoardResponse |
| DELETE | /api/v1/boards/{id} | 게시판 삭제 (ADMIN) | - | 204 |

### Post
| 메서드 | 경로 | 설명 | 요청 바디 | 응답 |
|--------|------|------|-----------|------|
| GET | /api/v1/boards/{boardId}/posts | 게시판별 글 목록 (페이징) | - | Page\<PostListResponse\> |
| POST | /api/v1/boards/{boardId}/posts | 글 작성 | PostCreateRequest | 201, PostResponse |
| GET | /api/v1/posts/{id} | 글 상세 조회 (조회수 +1) | - | PostResponse |
| PUT | /api/v1/posts/{id} | 글 수정 (작성자만) | PostUpdateRequest | PostResponse |
| DELETE | /api/v1/posts/{id} | 글 삭제 (작성자만) | - | 204 |

---

## 예외 처리 전략

### 커스텀 예외 계층
```
BusinessException (RuntimeException 상속, ErrorCode 보유)
├── NotFoundException      → 404
├── DuplicateException     → 409
├── UnauthorizedException  → 401
└── ForbiddenException     → 403
```

### ErrorResponse 형식
```json
{
  "code": "POST_NOT_FOUND",
  "message": "게시글을 찾을 수 없습니다.",
  "timestamp": "2026-06-12T10:00:00"
}
```

### @RestControllerAdvice 처리 범위
| 예외 | HTTP 상태 | 처리 방법 |
|------|-----------|-----------|
| NotFoundException | 404 | code + message |
| DuplicateException | 409 | code + message (DUPLICATE_USERNAME, DUPLICATE_EMAIL, NICKNAME_DUPLICATED, DUPLICATE_BOARD_NAME) |
| UnauthorizedException | 401 | code + message |
| ForbiddenException | 403 | code + message |
| MethodArgumentNotValidException | 400 | 필드별 검증 오류 목록 |
| Exception | 500 | 내부 오류 (상세 숨김, 로그만 기록) |

---

## 설계 결정사항 및 근거

1. **수동 HttpSession 인증 (단계 1)** — HTTP 세션 개념 자체를 학습시키기 위해
   Spring Security 인증 메커니즘(AuthenticationManager, UserDetailsService) 없이
   `HttpSession`을 직접 다룬다. 로그인 시 세션에 userId를 저장하고,
   컨트롤러에서 `@SessionAttribute`로 꺼내 사용자를 식별한다.
   비밀번호 해시는 BCrypt(PasswordEncoder)를 계속 사용한다.
   단계 2에서 UserDetailsService 기반 인증을 거쳐 stateless(JWT)로 전환할 예정.
2. **단방향 연관관계만 사용** — 입문자가 가장 많이 헤매는 양방향 매핑/순환 참조 문제를 차단한다.
3. **DTO와 Entity 분리** — record 기반 DTO로 Entity 직접 노출을 금지하는 습관을 처음부터 들인다.
4. **ddl-auto: update** — 강의 편의상 스키마 자동 생성. 운영에서는 validate + 마이그레이션 도구 사용을 주석으로 안내한다.
5. **회원가입 시 프로필 동시 생성** — 1:1 관계 트랜잭션 처리를 보여주는 강의 포인트.