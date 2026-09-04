---
type: 참조
track: reference
tags: [reference, curl]
---

# curl 테스트 가이드 — 전체 API & 에러 응답 (단계 3: Spring Security 표준)

**과정명**: 강의용 Spring Boot 게시판 — JWT + Spring Security 표준 통합 테스트
**브랜치**: `step3-spring-security`
**포트**: 8090 (`application.yaml`의 `server.port`)
**인증 방식**: JWT (Bearer 토큰) + Spring Security 표준 인가 — 세션/쿠키 없음

> 이 문서의 모든 명령은 실제 실행해 응답을 확인한 것이다. 상태 코드와 응답 본문이 문서와 일치해야 한다.
>
> **단계 2 → 3 변경점**: 인가가 Spring Security 선언적 방식으로 바뀌어, 권한 부족(ADMIN 아님)의 403 code가
> `ADMIN_ONLY` → **`ACCESS_DENIED`**(SecurityConfig의 hasRole 거부)로 바뀌었다. 글 소유권 403은
> `POST_ACCESS_DENIED` 그대로(서비스 검사). 401(`LOGIN_REQUIRED`)은 커스텀 EntryPoint가 응답한다.

---

## 0. 준비

```bash
# 1) (선택) DB 초기화가 필요하면 — 강의 시연 전 깨끗한 상태로
#    mysql -h127.0.0.1 -uroot -p1234 -e "DROP DATABASE IF EXISTS board; CREATE DATABASE board;"

# 2) 8090 포트 점유 확인 (이전 실행 잔존 프로세스 주의)
lsof -i :8090

# 3) 앱 실행
./gradlew bootRun     # Windows: .\gradlew.bat bootRun

# 4) 기동 확인 (200이면 OK)
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8090/api/v1/boards
```

편의를 위해 base URL을 변수로 둔다(이후 명령에서 `$B` 사용):

```bash
B=http://localhost:8090/api/v1
```

> **시드 계정**: 앱 기동 시 `DataInitializer`가 `admin` / `admin1234` (ROLE_ADMIN)을 자동 생성한다.

---

## 1. 회원가입 (signup)

```bash
curl -i -X POST $B/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"password123","nickname":"앨리스"}'
```

**기대**: `201 Created`

```json
{"id":2,"username":"alice","email":"alice@example.com","role":"USER"}
```

---

## 2. 로그인 → JWT 발급 (핵심)

```bash
curl -i -X POST $B/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'
```

**기대**: `200 OK` — **세션 쿠키(Set-Cookie) 없이** 토큰을 본문으로 받는다.

```json
{"accessToken":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI...","tokenType":"Bearer","expiresIn":3600}
```

> **단계 1(세션)과의 차이**: 세션 방식은 `Set-Cookie: JSESSIONID=...`를 받았다. JWT는 쿠키가 없고 응답 본문의 `accessToken`을 받는다.

### 토큰을 셸 변수에 저장 (이후 명령에서 재사용)

```bash
TOKEN=$(curl -s -X POST $B/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

echo "$TOKEN"   # eyJhbGci... 형태면 성공
```

> `jq`가 설치돼 있다면: `... | jq -r .accessToken`

---

## 3. 인증이 필요한 API — Bearer 토큰 사용

### 3-1. 내 프로필 조회 (토큰 있음 → 200)

```bash
curl -i -H "Authorization: Bearer $TOKEN" $B/profiles/me
```

**기대**: `200 OK`

```json
{"userId":2,"username":"alice","email":"alice@example.com","nickname":"앨리스",
 "bio":null,"phoneNumber":null,"birthDate":null,"profileImageUrl":null}
```

### 3-2. 토큰 없이 같은 요청 (→ 401)

```bash
curl -i $B/profiles/me
```

**기대**: `401 Unauthorized`

```json
{"code":"LOGIN_REQUIRED","message":"로그인이 필요합니다.","timestamp":"..."}
```

### 3-3. 변조/잘못된 토큰 (→ 401)

```bash
curl -i -H "Authorization: Bearer ${TOKEN}tampered" $B/profiles/me
```

**기대**: `401 Unauthorized` (`LOGIN_REQUIRED`) — 서명 검증 실패로 필터가 userId를 심지 않는다.

### 3-4. 내 프로필 수정

```bash
curl -i -X PUT $B/profiles/me \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"nickname":"앨리스2","bio":"안녕하세요","phoneNumber":"010-1234-5678","birthDate":"1995-03-01","profileImageUrl":null}'
```

**기대**: `200 OK` (수정된 프로필)

---

## 4. 게시판 (Board) — ADMIN 전용 생성

### 4-1. 일반 사용자가 게시판 생성 시도 (→ 403)

```bash
curl -i -X POST $B/boards \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"자유게시판","description":"아무 이야기나"}'
```

**기대**: `403 Forbidden` — SecurityConfig의 `hasRole("ADMIN")`이 막고, AccessDeniedHandler가 응답

```json
{"code":"ACCESS_DENIED","message":"접근 권한이 없습니다.","timestamp":"..."}
```

### 4-2. admin 토큰 발급 후 게시판 생성 (→ 201)

```bash
ADMIN=$(curl -s -X POST $B/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin1234"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

curl -i -X POST $B/boards \
  -H "Authorization: Bearer $ADMIN" \
  -H "Content-Type: application/json" \
  -d '{"name":"자유게시판","description":"아무 이야기나"}'
```

**기대**: `201 Created` (`{"id":1,"name":"자유게시판",...}`)

### 4-3. 게시판 목록/단건 조회 (공개 — 토큰 불필요)

```bash
curl -i $B/boards
curl -i $B/boards/1
```

**기대**: `200 OK`

### 4-4. 같은 이름으로 또 생성 (→ 409)

```bash
curl -i -X POST $B/boards \
  -H "Authorization: Bearer $ADMIN" \
  -H "Content-Type: application/json" \
  -d '{"name":"자유게시판","description":"중복"}'
```

**기대**: `409 Conflict` (`DUPLICATE_BOARD_NAME`)

---

## 5. 게시글 (Post)

### 5-1. 비로그인 글 작성 시도 (→ 401)

```bash
curl -i -X POST $B/boards/1/posts \
  -F 'post={"title":"제목","content":"내용"};type=application/json'
```

**기대**: `401 Unauthorized` (`LOGIN_REQUIRED`)

> 단계 10부터 글 작성/수정은 `multipart/form-data`다(`consumes = MULTIPART_FORM_DATA_VALUE`). 글 본문은 `post` 파트(JSON), 이미지는 `images` 파트(선택). 여기서는 토큰이 없어 컨트롤러 진입 전에 401이 나므로 본문 형식과 무관하게 실패한다.

### 5-2. alice가 글 작성 (→ 201)

```bash
# 이미지 없이 (post 파트만)
curl -i -X POST $B/boards/1/posts \
  -H "Authorization: Bearer $TOKEN" \
  -F 'post={"title":"alice의 첫 글","content":"JWT로 작성"};type=application/json'

# 이미지 첨부 (images 파트 반복 — 최대 5장)
curl -i -X POST $B/boards/1/posts \
  -H "Authorization: Bearer $TOKEN" \
  -F 'post={"title":"이미지 글","content":"첨부 있음"};type=application/json' \
  -F 'images=@a.png;type=image/png' \
  -F 'images=@b.png;type=image/png'
```

**기대**: `201 Created` — `authorUsername`이 `alice`로 들어간다(토큰에서 추출). 이미지를 올리면 `images` 배열에 `/images/{uuid}.png` URL이 담긴다.

> `-F 'post=...;type=application/json'`처럼 파트에 `type`을 명시해야 `@RequestPart("post")`가 Jackson으로 역직렬화한다. 빠뜨리면 파트가 `text/plain`으로 붙어 415가 난다. JSON 방식(`-H "Content-Type: application/json" -d ...`)으로 호출하면 415 `UNSUPPORTED_MEDIA_TYPE`다.

### 5-3. 글 목록(페이징) / 상세 조회 (공개)

```bash
curl -i "$B/boards/1/posts?page=0&size=10"   # 목록
curl -i $B/posts/1                            # 상세 (조회수 +1)
```

**기대**: `200 OK`. 상세를 두 번 호출하면 `viewCount`가 증가한다.

### 5-3b. 글 목록 — keyset(cursor) 방식 (단계 16, 공개)

무한스크롤용. 첫 요청은 커서 없이, 다음 요청은 직전 응답의 `lastCreatedAt`/`lastId`를
그대로 되돌려 보낸다([[DB-PERFORMANCE-WALKTHROUGH]]).

```bash
curl -s "$B/boards/1/posts/cursor?size=3"
```

**기대**: `items`(최신순) + `hasNext` + `lastCreatedAt` + `lastId`. `Page`와 달리
전체 건수(totalElements)는 없다 — COUNT를 아예 세지 않는 것이 keyset의 요점.

```bash
# 응답의 두 값을 그대로 붙여 다음 페이지 (값은 실제 응답으로 치환)
curl -s "$B/boards/1/posts/cursor?size=3&lastCreatedAt=2026-09-05T04:31:21.758962&lastId=1048585"
```

**기대**: 직전 페이지에 이어지는 3건 — 중복/누락 없음.

```bash
curl -i "$B/boards/1/posts/cursor?size=101"
```

**기대**: `400 INVALID_INPUT` — size 상한(100) 초과 거부.

### 5-4. 다른 사용자가 alice 글 수정 시도 (→ 403)

```bash
# bob 가입 + 로그인
curl -s -X POST $B/auth/signup -H "Content-Type: application/json" \
  -d '{"username":"bob","email":"bob@example.com","password":"password123","nickname":"밥"}' >/dev/null
BOB=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" \
  -d '{"username":"bob","password":"password123"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

curl -i -X PUT $B/posts/1 \
  -H "Authorization: Bearer $BOB" \
  -F 'post={"title":"탈취 시도","content":"남의 글"};type=application/json'
```

**기대**: `403 Forbidden` (`POST_ACCESS_DENIED`)

> 수정도 multipart다. JSON(`-H "Content-Type: application/json"`)으로 보내면 `consumes` 협상 단계에서 415가 나 `@PreAuthorize`(소유권 검사)까지 도달하지 못한다.

### 5-5. alice 본인 글 수정/삭제 (→ 200 / 204)

```bash
# 텍스트만 수정 (이미지 변경 없음)
curl -i -X PUT $B/posts/1 \
  -H "Authorization: Bearer $TOKEN" \
  -F 'post={"title":"수정한 제목","content":"본인은 가능"};type=application/json'

# 이미지 델타 수정: id=100 삭제 + new.png 추가 (deleteImageIds는 post 파트 JSON 안에)
curl -i -X PUT $B/posts/1 \
  -H "Authorization: Bearer $TOKEN" \
  -F 'post={"title":"수정한 제목","content":"본인은 가능","deleteImageIds":[100]};type=application/json' \
  -F 'images=@new.png;type=image/png'

curl -i -X DELETE $B/posts/1 -H "Authorization: Bearer $TOKEN"
```

**기대**: 수정 `200 OK`, 삭제 `204 No Content`

> 이미지는 **델타 방식**이다 — 언급하지 않은 기존 이미지는 유지되고, `deleteImageIds`의 id만 삭제, `images` 파트만 추가된다(자세한 규약은 FILE-UPLOAD.md §2).

---

## 6. 로그아웃

```bash
curl -i -X POST $B/auth/logout -H "Authorization: Bearer $TOKEN"
```

**기대**: `204 No Content`

> **단계 15부터**: `Authorization` 헤더를 주면 access token이 **즉시 폐기**된다(jti가 Redis denylist에 등록 — 이후 같은 토큰은 401). 헤더는 예전처럼 optional이라 안 주면 refresh만 폐기된다 — 동봉을 권장한다.

> **단계 1과의 차이**: 세션 방식은 `invalidate()`로 서버 세션을 지웠다. JWT는 원래 서버에 상태가 없어 할 일이 없었지만, 단계 15부터는 위처럼 denylist로 **강제 무효화**까지 된다([[REDIS-TOKEN]]).

### 6-1. denylist **이전**(단계 15 미적용) — "가짜 로그아웃" 문제 시연

단계 15 이전 코드의 로그아웃은 refresh token만 지웠다. 그때의 시나리오와 결과:

```bash
# 당시 코드 기준 시나리오 (현재 코드에서는 재현되지 않는다 — 아래 참고)
TOKEN=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

curl -s -o /dev/null -w "%{http_code}\n" $B/profiles/me -H "Authorization: Bearer $TOKEN"   # 200
curl -s -o /dev/null -w "%{http_code}\n" -X POST $B/auth/logout -H "Authorization: Bearer $TOKEN"  # 204

# 문제의 순간 — 로그아웃했는데도:
curl -s -o /dev/null -w "%{http_code}\n" $B/profiles/me -H "Authorization: Bearer $TOKEN"
# → 200  (만료까지 최대 1시간 동안 계속 200 — 서버가 access를 막을 방법이 없었다)
```

| 시점 | 응답 | 의미 |
|------|------|------|
| 로그아웃 직후 같은 access | **200** | stateless JWT의 구조적 한계 — "로그아웃"은 사실 refresh 재발급 차단일 뿐 |
| access 만료(1시간) 후 | 401 | 자연 만료가 유일한 무효화 수단이었다 |

> 이 동작을 직접 재현하고 싶다면 단계 15 커밋 직전으로 이동해 실행한다:
> `git checkout 7e592a5^` → 실행·시연 → `git checkout feature/redis-token-store`.
> (당시 logout은 쿠키의 refreshToken만 사용하고 Authorization 헤더는 무시했다)

### 6-2. denylist **이후**(단계 15) — 즉시 폐기 검증 시나리오

같은 절차가 이제 어떻게 달라지는지 + Redis에서 무슨 일이 일어나는지까지 관찰한다.

```bash
# ① 로그인 — access는 변수에, refresh(httpOnly 쿠키)는 cookies.txt에
TOKEN=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" -c cookies.txt \
  -d '{"username":"alice","password":"password123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# ② 정상 동작 확인
curl -s -o /dev/null -w "%{http_code}\n" $B/profiles/me -H "Authorization: Bearer $TOKEN"   # 200

# ③ (관찰용) 이 토큰의 jti 확인 — 로그아웃 후 deny 키와 대조할 값
echo "$TOKEN" | cut -d '.' -f 2 | python3 -c "
import sys, base64, json
s = sys.stdin.read().strip()
print(json.loads(base64.urlsafe_b64decode(s + '=' * (-len(s) % 4)))['jti'])
"

# ④ 로그아웃 — Authorization 헤더 동봉이 핵심 (access 즉시 폐기의 재료)
curl -s -o /dev/null -w "%{http_code}\n" -X POST $B/auth/logout \
  -H "Authorization: Bearer $TOKEN" -b cookies.txt                                          # 204

# ⑤ 단계 15의 핵심 순간 — 같은 access가 "즉시" 거부된다
curl -s -o /dev/null -w "%{http_code}\n" $B/profiles/me -H "Authorization: Bearer $TOKEN"   # 401

# ⑥ refresh도 함께 폐기됐다 — 재발급 시도 401
curl -s -o /dev/null -w "%{http_code}\n" -X POST $B/auth/reissue -b cookies.txt             # 401

# ⑦ Redis에서 실체 확인 — ③의 jti가 deny 키로 올라와 있다
docker exec board-redis redis-cli KEYS 'deny:*'     # 1) "deny:{③의 jti}"
docker exec board-redis redis-cli TTL "deny:{③의 jti 붙여넣기}"   # ≤3600 (access 잔여 수명)
docker exec board-redis redis-cli KEYS 'rt:*'       # (empty) — refresh 2키도 삭제됨
```

전/후 비교 요약:

| 검증 항목 | 단계 15 이전 | 단계 15 이후 |
|-----------|--------------|--------------|
| ⑤ 로그아웃 직후 같은 access | 200 (최대 1시간 유효) | **401 즉시** |
| ⑥ 옛 refresh로 재발급 | 401 | 401 (동일) |
| ⑦ Redis | (사용 안 함) | `deny:{jti}` 생성, `rt:*` 삭제 |

> **환경 메모**: 위 명령은 이 문서의 전제(IDE/JAR 직접 실행, `B=http://localhost:8090/api/v1`)
> 기준이다. 도커 compose로 띄웠다면 백엔드 포트가 비공개이므로 `B=http://localhost/api/v1`
> (nginx 80 프록시 경유)로 바꾼다. Redis 관찰(⑦)은 두 경우 모두 `board-redis` 컨테이너
> 기준이며, IDE 실행 시엔 앱이 붙는 localhost:6379 Redis를 대상으로 한다.

---

## 7. 에러 응답 모음 (GlobalExceptionHandler 검증)

모든 에러는 동일한 형식 `{code, message, timestamp}` (검증 실패는 `errors` 배열 추가)로 응답한다.

### 7-1. 입력값 검증 실패 (→ 400, 필드별 오류)

```bash
curl -i -X POST $B/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"username":"a","email":"bad","password":"1","nickname":""}'
```

**기대**: `400` (`INVALID_INPUT`) — `errors` 배열에 필드별 사유

```json
{"code":"INVALID_INPUT","message":"입력값이 올바르지 않습니다.","timestamp":"...",
 "errors":[{"field":"username","reason":"크기가 4에서 50 사이여야 합니다"},
           {"field":"email","reason":"올바른 형식의 이메일 주소여야 합니다"}]}
```

### 7-2. 깨진 JSON 본문 (→ 400) — *이번 단계 보강*

```bash
curl -i -X POST $B/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": broken'
```

**기대**: `400` (`MALFORMED_REQUEST`, "요청 본문(JSON)을 읽을 수 없습니다.")

### 7-3. 경로 변수 타입 불일치 (→ 400) — *보강*

```bash
curl -i $B/posts/abc        # id 자리에 숫자가 아닌 문자
```

**기대**: `400` (`TYPE_MISMATCH`, "요청 값의 타입이 올바르지 않습니다.")

### 7-4. 지원하지 않는 HTTP 메서드 (→ 405) — *보강*

```bash
curl -i -X DELETE $B/auth/login
```

**기대**: `405` (`METHOD_NOT_ALLOWED`, "지원하지 않는 HTTP 메서드입니다.")

### 7-5. Content-Type 누락/미지원 (→ 415) — *보강*

```bash
curl -i -X POST $B/auth/login -d 'username=x'   # Content-Type: application/json 없음
```

**기대**: `415` (`UNSUPPORTED_MEDIA_TYPE`, "지원하지 않는 미디어 타입입니다.")

### 7-6. 없는 리소스 (→ 404)

```bash
curl -i $B/posts/999999
```

**기대**: `404` (`POST_NOT_FOUND`)

### 7-7. 중복 (→ 409)

```bash
# alice 재가입 시도
curl -i -X POST $B/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"password123","nickname":"앨리스"}'
```

**기대**: `409` (`DUPLICATE_USERNAME`)

### 7-8. 로그인 실패 (→ 401)

```bash
curl -i -X POST $B/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"wrong"}'
```

**기대**: `401` (`LOGIN_FAILED`, "username 또는 password가 올바르지 않습니다.")
> 존재하지 않는 username도 같은 `LOGIN_FAILED`로 응답한다(계정 존재 여부 노출 방지).

---

## 8. 상태 코드 / ErrorCode 요약표

| 상황 | 상태 | code |
|------|------|------|
| 조회 성공 | 200 | — |
| 생성 성공 (signup, board, post) | 201 | — |
| 삭제/로그아웃 성공 | 204 | — |
| 입력값 검증 실패 | 400 | `INVALID_INPUT` (+errors) |
| 깨진 JSON 본문 | 400 | `MALFORMED_REQUEST` |
| 경로/파라미터 타입 불일치 | 400 | `TYPE_MISMATCH` |
| 필수 파라미터 누락 | 400 | `MISSING_PARAMETER` |
| 비로그인 / 토큰 불량 | 401 | `LOGIN_REQUIRED` (EntryPoint) |
| 로그인 실패 | 401 | `LOGIN_FAILED` |
| 작성자 아님 (소유권) | 403 | `POST_ACCESS_DENIED` (서비스) |
| 관리자 아님 (role) | 403 | `ACCESS_DENIED` (hasRole 거부) |
| 리소스 없음 | 404 | `*_NOT_FOUND` |
| 지원 않는 메서드 | 405 | `METHOD_NOT_ALLOWED` |
| 중복 | 409 | `DUPLICATE_*` / `NICKNAME_DUPLICATED` |
| 미지원 미디어 타입 | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 예상치 못한 서버 오류 | 500 | `INTERNAL_ERROR` |
| OAuth state 불일치 (단계 7) | 401 | `INVALID_OAUTH_STATE` |
| 카카오 인가/토큰 교환 실패 (단계 7) | 401 | `OAUTH_LOGIN_FAILED` |
| 매핑 없는 경로 (단계 7 보강) | 404 | `RESOURCE_NOT_FOUND` |

---

## 9. 전체 흐름 한 번에 (복붙용 스크립트)

```bash
B=http://localhost:8090/api/v1

# 가입 + 로그인 → 토큰
curl -s -X POST $B/auth/signup -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","password":"password123","nickname":"앨리스"}' >/dev/null
TOKEN=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")

# admin 토큰 → 게시판 생성
ADMIN=$(curl -s -X POST $B/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin1234"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
curl -s -X POST $B/boards -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{"name":"자유게시판","description":"아무 이야기나"}' >/dev/null

# 글 작성(multipart) → 조회
curl -s -X POST $B/boards/1/posts -H "Authorization: Bearer $TOKEN" \
  -F 'post={"title":"제목","content":"내용"};type=application/json'
curl -s "$B/boards/1/posts?page=0&size=10"
```

---

## 10. 단계 7 — 카카오 OAuth2 로그인 (브라우저 + curl)

> **전제**: 프로젝트 루트에 `.env`(카카오 REST API 키/Secret)가 있어야 한다 — 없으면 기동 자체가 실패한다(fail-fast, `.env.example` 참고). 카카오 콘솔에 Redirect URI `http://localhost:8090/api/oauth/kakao/callback` 등록도 필수.

OAuth 경로는 `/api/v1`이 아니므로 변수를 따로 둔다:

```bash
O=http://localhost:8090/api/oauth/kakao
```

### 10-1. 왜 curl만으로는 안 되나

전체 흐름 중 **④(카카오 로그인/동의 화면)는 사람이 브라우저에서 통과해야 한다.** curl은 그 앞(302 관찰)과 뒤(발급된 토큰 사용, 실패 케이스)를 검증하는 데 쓴다.

### 10-2. 로그인 시작을 curl로 관찰 — 302 + state 쿠키

```bash
curl -si $O/login | grep -iE "^(HTTP|location|set-cookie)"
```

**기대**: `302` — Location은 카카오 인가 URL, state 쿠키가 함께 심긴다.

```
HTTP/1.1 302
Location: https://kauth.kakao.com/oauth/authorize?client_id=...&redirect_uri=...&response_type=code&state=<uuid>
Set-Cookie: oauthState=<uuid>; Path=/api/oauth/kakao; Max-Age=300; HttpOnly; SameSite=Lax
```

> **관찰 포인트**: Location의 `state`와 쿠키의 `oauthState`가 **같은 값**이다(콜백에서 대조할 쌍). 쿠키가 `SameSite=Lax`인 이유는 콜백이 카카오發 크로스 사이트 이동이기 때문 — refresh 쿠키(Strict)와 비교해 보라.

### 10-3. 실제 로그인 (브라우저 필수)

```
1. 브라우저에서 http://localhost:8090/api/oauth/kakao/login 접속
2. 카카오 로그인/동의 → JSON 응답: {"accessToken":"eyJ...","tokenType":"Bearer","expiresIn":3600}
3. accessToken 값을 복사해 셸 변수로:
   KAKAO=eyJ...   (붙여넣기)
```

### 10-4. 발급된 토큰으로 API 호출 — 로컬 로그인과 동일하게 동작

```bash
curl -i -H "Authorization: Bearer $KAKAO" $B/profiles/me
```

**기대**: `200 OK` — username이 `kakao_{회원번호}`, 닉네임은 카카오 프로필 닉네임.

```json
{"userId":19,"username":"kakao_4614955682","email":"...","nickname":"김은범", ...}
```

DB 확인:

```bash
mysql -h127.0.0.1 -uroot -p1234 board \
  -e "SELECT username, provider, provider_id FROM users WHERE provider='KAKAO';"
```

### 10-5. 실패 케이스 (curl로 검증 가능)

```bash
# (a) state 쿠키 없이 콜백 → 401 INVALID_OAUTH_STATE (CSRF 방어 동작)
curl -i "$O/callback?code=fake&state=x"

# (b) 사용자가 동의 화면에서 [취소] → 카카오가 error 파라미터로 돌려보낸다 → 401
curl -i "$O/callback?error=access_denied&error_description=User%20denied"

# (c) state는 통과했지만 가짜 code → 카카오 토큰 교환 실패 → 401
#     (쿠키와 파라미터에 같은 값을 넣어 state 검증을 통과시키는 트릭)
curl -i --cookie "oauthState=abc" "$O/callback?code=fake-code&state=abc"

# (d) 오타 URL (/login 누락) → 404
curl -i $O
```

**기대**:

| 케이스 | 상태 | code |
|--------|------|------|
| (a) state 불일치/쿠키 없음 | 401 | `INVALID_OAUTH_STATE` |
| (b) 동의 거부 (error 파라미터) | 401 | `OAUTH_LOGIN_FAILED` |
| (c) 가짜/만료 code | 401 | `OAUTH_LOGIN_FAILED` |
| (d) 매핑 없는 경로 | 404 | `RESOURCE_NOT_FOUND` |

> (b)와 (c)가 같은 `OAUTH_LOGIN_FAILED`로 응답하는 것은 의도다 — 세부 사유는 서버 로그에만 남기고 밖으로는 노출하지 않는다. (c)를 반복 실행하면 서버 로그에서 "카카오 token 요청 실패"를 확인할 수 있다.

### 10-6. 재발급/로그아웃 — 브라우저에서

refresh token은 httpOnly 쿠키라 **curl은 값을 알 수 없다**(그게 목적이다). 로그인했던 브라우저의 개발자도구 콘솔에서:

```javascript
// 재발급 — 쿠키는 브라우저가 자동 동봉
await fetch('/api/v1/auth/reissue', {method: 'POST'}).then(r => r.json())
// → {accessToken: "eyJ...", tokenType: "Bearer", expiresIn: 3600}

// 로그아웃 — 서버 저장소(Redis)의 refresh 삭제 + 쿠키 만료
await fetch('/api/v1/auth/logout', {method: 'POST'}).then(r => r.status)  // 204
```

---

## 부록. 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| 토큰 있는데 401 | `Authorization: Bearer ` 형식 오류 (Bearer 뒤 공백, 토큰 오타) | 헤더 형식 확인, `echo "$TOKEN"`로 값 점검 |
| 모든 요청 401 | `$TOKEN`이 비어 있음(로그인 응답 파싱 실패) | 로그인부터 `-i`로 200·본문 확인 |
| 기동 실패 (Port 8090 in use) | 이전 실행 잔존 프로세스 | `lsof -i :8090` → `kill <PID>` |
| board 생성 403 | 일반 사용자 토큰 사용 | `admin`/`admin1234` 토큰 사용 |
| 글 작성 시 404 BOARD_NOT_FOUND | 게시판이 아직 없음 | admin으로 게시판 먼저 생성 |
| `python3` 없음 | 토큰 추출 파이프 실패 | `jq -r .accessToken`로 대체하거나 응답에서 수동 복사 |
