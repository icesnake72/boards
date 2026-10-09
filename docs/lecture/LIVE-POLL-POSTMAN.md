---
step: 18
track: domain
tags: [live, sse, postman, test, practice]
requires: ["[[LIVE-POLL]]", "[[LIVE-POLL-WALKTHROUGH-DAY1]]"]
status: 완료
---

# 단계 18 Postman 실습 — 라이브 질문·투표를 손으로 테스트하기

> 기준 Postman: v11 이상(SSE 응답 표시는 v10.10부터 지원). 서버는 로컬 도커(`http://localhost`)를 권장한다 —
> 운영(`https://sbs.alldayai.org`)에서 하면 운영 DB에 계정·이벤트가 실제로 생긴다.

Postman에서는 **요청 탭 하나로 SSE 스트림을 열어 두고, 다른 탭에서 질문·좋아요·투표를 보내**, 스트림 탭에 이벤트가 쌓이는 것을 눈으로 확인한다.
화면(React) 없이 API만으로 단계 18의 전체 흐름 — 쓰기는 REST + Bearer, 받기는 공개 SSE — 을 체험하는 실습이다.

학습 목표 — 이 실습을 마치면 다음을 할 수 있다.

- 환경 변수와 Post-response 스크립트로 토큰·id를 자동 저장하며 요청을 이어 붙인다
- SSE 요청을 열어 두고 다른 요청이 만든 변화를 실시간으로 확인한다
- 일부러 실패시켜 401·403·404·409·400이 각각 어떤 규칙에서 나오는지 구분한다
- Postman에서 흔히 생기는 함정(URL 붙여넣기, 인증 미설정, 변수 conflict)을 스스로 진단한다

---

## 1. 한눈에 보기

| 순서 | 할 일 | 요청 | 토큰 | 저장하는 변수 |
|------|-------|------|------|---------------|
| 0 | 환경 만들기 | — | — | `baseUrl` 등 |
| 1 | 계정 2개(처음 한 번) | `POST /api/v1/auth/signup` × 2 | 없음 | — |
| 2 | 로그인 | `POST /api/v1/auth/login` × 2 | 없음 | `hostToken`, `guestToken` |
| 3 | 이벤트 만들기 | `POST /api/v1/live/events` | host | `code` |
| 4 | **스트림 열어 두기** | `GET /api/v1/live/events/{{code}}/stream` | **없음(No Auth)** | — |
| 5-1 | 질문 | `POST /api/v1/live/events/{{code}}/questions` | guest | `questionId` |
| 5-2 | 좋아요 | `POST /api/v1/live/questions/{{questionId}}/like` | host | — |
| 5-3 | 투표 만들기 | `POST /api/v1/live/events/{{code}}/polls` | host | `pollId`, `optionId` |
| 5-4 | 투표하기 | `POST /api/v1/live/polls/{{pollId}}/votes` | guest | — |
| 6 | 일부러 실패시키기 | 여러 개 | 다양 | — |
| 7 | 스냅샷 보기·종료 | `GET /api/v1/live/events/{{code}}`, `POST .../close` | guest / host | — |

> [!IMPORTANT]
> 모든 요청에서 **URL 칸에는 `{{baseUrl}}/api/...` 주소만** 넣는다. 토큰은 URL이 아니라 항상 **Authorization 탭**에 넣는다. 이 둘을 섞으면 404·401이 난다(§9).

---

## 2. 준비 — 환경(Environment) 만들기

왼쪽 **Environments → +** 로 `live-local`을 만들고 변수를 추가한다. 오른쪽 위 환경 선택 칸에서 **`live-local`을 반드시 선택**한다(선택하지 않으면 스크립트가 값을 저장할 곳이 없다).

| 변수 | 값 |
|------|----|
| `baseUrl` | `http://localhost`(로컬 도커) · `http://localhost:8090`(`./gradlew bootRun`) · `https://sbs.alldayai.org`(운영) — **끝에 `/` 없이** |
| `hostToken`, `guestToken`, `code`, `questionId`, `pollId`, `optionId` | 비워 둔다 — 아래 스크립트가 채운다 |

모든 요청의 Body는 **raw → JSON**이다. 인증이 필요한 요청은 **Authorization 탭 → Auth Type `Bearer Token` → Token `{{hostToken}}` 또는 `{{guestToken}}`** 으로 설정한다.

> 팁: 요청들을 Collection 하나에 모으고 **Collection의 Authorization**을 `Bearer Token` / `{{hostToken}}`으로 정해 두면, 각 요청은 기본값(`Inherit auth from parent`)만으로 토큰이 따라간다. 참여자로 보낼 요청(5-1, 5-4, 7의 스냅샷)만 그 요청의 Authorization을 `{{guestToken}}`으로 바꾼다.

---

## 3. 계정 만들기와 로그인

### 3-1. 회원가입 (처음 한 번)

| 항목 | 값 |
|------|----|
| 메서드 / URL | `POST` `{{baseUrl}}/api/v1/auth/signup` |
| Authorization | No Auth |
| 기대 응답 | `201` |

두 번 보낸다. 입력 규칙: username 4~50자, password 8자 이상, email 형식, nickname 필수(중복 불가).

```json
{ "username": "hostuser01", "email": "hostuser01@example.com", "password": "password123", "nickname": "진행자01" }
```

```json
{ "username": "guestuser01", "email": "guestuser01@example.com", "password": "password123", "nickname": "참여자01" }
```

### 3-2. 로그인 → 토큰 저장

| 항목 | 값 |
|------|----|
| 메서드 / URL | `POST` `{{baseUrl}}/api/v1/auth/login` |
| Body | `{ "username": "hostuser01", "password": "password123" }` |
| 기대 응답 | `200`, `accessToken` 포함 |

**Scripts → Post-response**(구버전은 **Tests** 탭):

```js
pm.environment.set("hostToken", pm.response.json().accessToken);
```

같은 요청에서 Body의 username을 `guestuser01`로, 스크립트의 `hostToken`을 `guestToken`으로 바꿔 한 번 더 보낸다.

> 주의: access token은 **1시간**(`jwt.access-token-validity-seconds: 3600`) 뒤 만료된다. 브라우저 화면은 refresh 쿠키로 자동 재발급하지만 Postman은 그렇지 않다 — 1시간 뒤 401이 나면 이 단계를 다시 보낸다.

---

## 4. 이벤트 만들기 (진행자)

| 항목 | 값 |
|------|----|
| 메서드 / URL | `POST` `{{baseUrl}}/api/v1/live/events` |
| Authorization | Bearer `{{hostToken}}` |
| Body | `{ "title": "포스트맨 실습" }` |
| 기대 응답 | `201`, `"code": "K7M2QX"` 같은 6자 코드, `"host": true` |

Post-response 스크립트:

```js
pm.environment.set("code", pm.response.json().code);
```

---

## 5. 스트림 열어 두기 — 이 탭은 끝까지 닫지 않는다

**새 탭**에서:

| 항목 | 값 |
|------|----|
| 메서드 / URL | `GET` `{{baseUrl}}/api/v1/live/events/{{code}}/stream` |
| Authorization | **No Auth** — 스트림은 공개다(브라우저 `EventSource`가 헤더를 못 붙이기 때문, [[LIVE-POLL]] §4) |

Send하면 요청이 끝나지 않고 계속 열려 있다. 응답 영역에 `connected` 이벤트가 보이면 성공이다.

- 같은 스트림 탭을 여러 개 열어도 된다 — 강의실 참여자 여러 명을 흉내 낸다. 모든 탭이 같은 이벤트를 받는다.
- 응답이 비어 있거나 로딩만 돈다면(구버전 Postman) 이 단계만 터미널에서 `curl -N {baseUrl}/api/v1/live/events/{코드}/stream`으로 대신한다.

> 참고: 이벤트 이름 옆 배지 색(노랑·빨강 등)은 **Postman이 화면에서 붙이는 표시**다. 서버는 모든 탭에 글자 하나 다르지 않은 같은 이벤트를 보낸다(SSE에는 색·중요도를 실을 자리가 없다). 같은 `poll.voted`가 탭마다 다른 색으로 보여도 정상이다 — 탭을 연 시점이 달라 그 탭에서 이벤트 이름이 처음 등장한 순서가 다르기 때문으로 보인다(Postman 공식 문서에 색 배정 규칙은 없다).

---

## 6. 다른 탭에서 하나씩 보내고, 스트림 탭 확인하기

| 순서 | 메서드 / URL | Authorization | Body |
|------|--------------|---------------|------|
| 5-1 | `POST` `{{baseUrl}}/api/v1/live/events/{{code}}/questions` | `{{guestToken}}` | `{ "content": "들리나요?" }` |
| 5-2 | `POST` `{{baseUrl}}/api/v1/live/questions/{{questionId}}/like` | `{{hostToken}}` | 없음 |
| 5-3 | `POST` `{{baseUrl}}/api/v1/live/events/{{code}}/polls` | `{{hostToken}}`(진행자만) | `{ "title": "점심은?", "options": ["김밥", "라면"] }` |
| 5-4 | `POST` `{{baseUrl}}/api/v1/live/polls/{{pollId}}/votes` | `{{guestToken}}` | `{ "optionId": {{optionId}} }` — 숫자라 따옴표 없이 |

| 순서 | 이 요청의 응답 | 스트림 탭에 붙는 이벤트 |
|------|----------------|------------------------|
| 5-1 | `201`, `"mine": true` | `question.created` (`mine: false` — 공개 스트림엔 개인 정보 없음) |
| 5-2 | `200`, `{"liked": true, "likeCount": 1}` | `question.liked` |
| 5-3 | `201` | `poll.created` |
| 5-4 | `200`, `"myOptionId"`가 고른 선택지 | `poll.voted` |

Post-response 스크립트:

```js
// 5-1 — 진단 로그를 함께 남긴다(문제가 생기면 Console에서 바로 원인이 보인다)
const body = pm.response.json();
console.log("5-1 응답코드:", pm.response.code, "| id:", body.id, "| 활성 환경:", pm.environment.name);
pm.test("질문 생성 201", () => pm.response.to.have.status(201));
if (body.id) {
  pm.environment.set("questionId", String(body.id));
  console.log("저장 후 값:", pm.environment.get("questionId"));
}
```

```js
// 5-3
const p = pm.response.json();
pm.environment.set("pollId", p.id);
pm.environment.set("optionId", p.options[0].id);
```

5-2를 한 번 더 보내면 좋아요가 취소되어 `"liked": false`가 오고, 스트림에는 `question.liked`가 또 붙는다(토글).

---

## 7. 일부러 실패시켜 보기 — 규칙 확인

| 해 볼 것 | 방법 | 기대 응답 | 지켜지는 규칙 |
|----------|------|-----------|---------------|
| 재투표 | 5-4를 한 번 더 | `409` `ALREADY_VOTED` | 1인 1표(Redis `HSETNX`) |
| 참여자가 이벤트 종료 | `POST {{baseUrl}}/api/v1/live/events/{{code}}/close` + `{{guestToken}}` | `403` | 진행자만(`@liveSecurity`) |
| 토큰 없이 질문 | 5-1을 No Auth로 | `401` `LOGIN_REQUIRED` | 쓰기는 로그인 필수 |
| 빈 질문 | 5-1 Body `{ "content": "  " }` | `400` `INVALID_INPUT` | `@NotBlank` |
| 선택지 1개 투표 | 5-3 Body `"options": ["김밥"]` | `400` | `@Size(min = 2, max = 5)` |
| 없는 코드 구독 | 스트림 URL 코드를 `ZZZZZZ`로 | `404` `LIVE_EVENT_NOT_FOUND` | 코드 존재 확인 |
| 소문자 코드 | 스트림·스냅샷 URL의 코드를 소문자로 | 정상 | 서버가 대문자로 정규화 |

---

## 8. 스냅샷 보기와 마무리

1. `GET {{baseUrl}}/api/v1/live/events/{{code}}` + `{{guestToken}}`
   - 질문 목록(좋아요 순), 각 질문의 `likedByMe`·`mine`, 투표의 `myOptionId`가 보인다.
   - 토큰을 `{{hostToken}}`으로 바꾸면 같은 데이터인데 "내 상태" 값만 달라진다 — 개인 정보는 스트림이 아니라 **인증된 스냅샷에만** 있다.
2. `POST {{baseUrl}}/api/v1/live/events/{{code}}/close` + `{{hostToken}}` → `204`. 스트림 탭에 `event.closed`가 붙고 스트림이 끝난다.
3. 스트림 탭을 다시 Send → `connected`와 `event.closed`만 받고 바로 끝난다(종료된 이벤트는 다시 열리지 않음).

---

## 9. 문제 해결 — 실습 중 실제로 만난 것들

서버는 아래 경우를 **같은 응답**으로 돌려줘서 응답만으로는 원인이 구분되지 않는다. 그래서 Postman **Console**(아래쪽, 또는 View → Show Postman Console)에서 **실제로 나간 URL과 헤더**를 먼저 본다.

### 9-1. `404 RESOURCE_NOT_FOUND` — URL 칸에 주소가 아닌 글자가 섞임

| URL 칸 | 응답 |
|--------|------|
| `{{baseUrl}}/api/v1/live/events` | `201` 정상 |
| `{{baseUrl}}/api/v1/live/events · Bearer {{hostToken}}` (설명을 통째로 붙여넣음) | `404 RESOURCE_NOT_FOUND` |
| `{{baseUrl}}/api/v1/live/events ` (끝에 공백) | `404 RESOURCE_NOT_FOUND` |
| `{{baseUrl}}/api/v1/live/event` (s 빠짐) | `404 RESOURCE_NOT_FOUND` |
| 메서드를 GET으로 | `405 METHOD_NOT_ALLOWED`(다른 증상) |

Console에서 요청 줄 끝에 `%20`(공백)이나 `%C2%B7`(가운뎃점)이 붙어 있으면 URL 칸에 남은 글자다.

### 9-2. `401 LOGIN_REQUIRED` — 토큰이 안 실렸거나, `questionId`가 비었다

| 상황 | 응답 |
|------|------|
| Authorization 탭이 No Auth / `Inherit`인데 부모에 인증 없음 | 401 |
| Token 칸에 `Bearer eyJ...`처럼 `Bearer`까지 입력 | 401 |
| `{{hostToken}}`이 빨간색(변수를 못 찾음 — 이름 오타, 환경 미선택) | 401 |
| 토큰 만료(1시간) 또는 그 계정으로 로그아웃한 옛 토큰 | 401 |
| `baseUrl`은 운영인데 로컬에서 발급한 토큰(서명 키가 다르다) | 401 |
| **5-2에서 `questionId`가 빈 값** → URL이 `.../questions//like` | **401** — 토큰이 정상이어도 이렇게 보인다 |
| `questionId` 변수가 아예 없음 → `.../questions/{{questionId}}/like` | `400 TYPE_MISMATCH`(다른 증상) |

Console의 Request Headers에 `Authorization: Bearer eyJ...`가 있는지, 요청 URL에 실제 숫자 id가 들어갔는지 확인한다.

### 9-3. 스크립트는 저장했다는데 environment에 값이 안 보이고 conflict가 뜬다

- 5-1의 진단 로그에 `저장 후 값`이 숫자로 찍혔다면 **저장은 성공**한 것이다.
- 원인은 **environment 편집 탭을 열어 둔 채** 요청을 보낸 것이다. 편집 탭은 스크립트가 바꾸기 전의 값(빈 값)을 들고 있어서, 스크립트가 쓴 새 값과 부딪쳐 conflict로 보인다.
- 해결:
  1. 편집 탭을 **저장하지 않고(Don't save / Discard)** 닫는다. 여기서 저장하면 빈 값이 스크립트 값을 덮어쓴다.
  2. 값은 오른쪽 위 **눈 모양 아이콘(Quick Look)** 으로 확인한다.
- 예방: 스크립트가 변수를 바꾸는 동안 environment 편집 탭을 열어 두지 않는다.

> 팁: 급하면 environment의 `questionId`에 스냅샷(§8)에서 본 질문 id를 직접 입력해도 된다.

### 9-4. 5-1을 여러 번 보냈더니 질문이 여러 개

Send할 때마다 질문이 하나씩 생긴다(정상). 스냅샷(§8)으로 목록을 보고, `questionId`는 마지막으로 저장된 값(가장 최근 질문)이 쓰인다.

---

## 10. 정리 — Postman 실습으로 확인한 것

| 확인한 설계 | 어디서 보였나 |
|-------------|---------------|
| 쓰기는 REST + Bearer, 받기는 공개 SSE | 4·5단계 Authorization 차이 |
| 스트림엔 개인 정보 없음, 스냅샷에만 | `question.created`의 `mine: false` vs 5-1 응답의 `mine: true` |
| 변화가 모든 구독자에게 동시에 | 스트림 탭 여러 개에 같은 이벤트 |
| 1인 1표·진행자 권한·입력 검증 | §7의 409·403·400 |
| 종료된 이벤트는 재연결해도 닫힘 | §8의 3 |

화면으로 같은 흐름을 보려면 board 프론트의 "라이브" 탭([[LIVE-POLL-WALKTHROUGH-DAY2]]), 터미널로 보려면 curl 두 개([[LIVE-POLL-WALKTHROUGH-DAY1]] Step 15)를 쓴다.
