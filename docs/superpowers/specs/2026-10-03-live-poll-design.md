---
type: spec
step: 18
tags: [spec, live, sse, redis, poll]
status: approved-design
---

# 단계 18 — 실시간 질문·투표 (Slido 스타일) 설계

> 2026-10-03 확정. chat 후속 강의로 별도 `poll` 프로젝트를 검토했으나, 2GB 서버에 세 번째 JVM을
> 띄우지 않기 위해 **board 앱 안의 단계 18**로 만든다. 분량은 4시간 × 2일.

## 1. 목표와 성공 기준

| 항목 | 내용 |
|---|---|
| 학습 목표 | (1) SSE(`SseEmitter`) 단방향 푸시 — "왜 이번엔 WebSocket이 아닌가" (2) Redis ZSET 순위 · HASH 카운터 집계 (3) Lua script로 "확인 → 증가" 원자화 |
| 기능 | 진행자가 이벤트를 만들고 참여 코드를 공유 → 참여자가 질문 등록·좋아요 → 좋아요 순 실시간 정렬 → 진행자가 객관식 투표를 열고 마감 → 결과 막대 실시간 갱신 |
| 성공 기준 | 브라우저 두 개(진행자/참여자)에서 질문·좋아요·투표가 새로고침 없이 1초 안에 서로 반영된다. `./scripts/verify.sh` 통과. walkthrough만 보고 같은 결과를 재현할 수 있다 |

## 2. 결정 사항

| 결정 | 선택 | 이유 |
|---|---|---|
| 위치 | board 앱 내부 `com.example.board.live` | JVM 추가 없음, board 인증 그대로 사용 |
| 참여자 인증 | board 로그인 필수 | 중복 투표를 userId로 확실히 차단, 수업 범위를 SSE·Redis에 집중 |
| SSE 구독 인증 | 구독(GET stream)은 공개, 쓰기는 Bearer 필수 | 브라우저 `EventSource`는 헤더를 못 붙인다. 스트림은 읽기 전용·공개 가능한 정보만 싣는다 |
| 저장 | MySQL = 메타데이터, Redis = 실시간 집계 (A안) | 영속 구조와 휘발성 숫자의 분리 |
| 프론트 | `board/frontend`에 "Live" 탭 통합 | chat UI와 같은 방식, 기존 `api.js` 인증 재사용 |
| 다중 인스턴스 | 미지원 (단일 인스턴스 가정) | YAGNI. Redis Pub/Sub 확장은 문서의 "다음 단계"로만 |

## 3. 도메인 (MySQL, JPA)

모두 `BaseTimeEntity`를 상속한다.

| 엔티티 | 필드 | 제약 |
|---|---|---|
| `LiveEvent` | `id`, `host`(User, LAZY), `title`, `code`, `status`(`LiveEventStatus`: OPEN/CLOSED) | `code` unique, 6자 |
| `Question` | `id`, `event`(LiveEvent, LAZY), `author`(User, LAZY), `content` | content 1~300자 |
| `Poll` | `id`, `event`(LiveEvent, LAZY), `title`, `status`(`PollStatus`: OPEN/CLOSED), `options`(OneToMany, cascade ALL, orderBy sortOrder) | 생성 즉시 OPEN |
| `PollOption` | `id`, `poll`(Poll, LAZY), `text`, `sortOrder` | 2~5개, 각 1~50자 |

- 참여 코드: 혼동 문자(`0 O 1 I`)를 뺀 `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`에서 `SecureRandom`으로 6자. `existsByCode`가 참이면 최대 5회 재시도, 실패 시 `INTERNAL_ERROR`.
- 좋아요 수·득표수는 엔티티에 없다(Redis가 원천).
- 질문 삭제는 hard delete (+ Redis 키 정리).

## 4. API (`/api/v1/live`)

| 메서드 | 경로 | 권한 | 요청 → 응답 |
|---|---|---|---|
| POST | `/events` | 로그인 | `{title}` → 201 `LiveEventResponse{id, code, title, status, hostNickname, host:true}` |
| GET | `/events/mine` | 로그인 | → `List<LiveEventResponse>` (최신순) |
| GET | `/events/{code}` | 로그인 | → `LiveSnapshotResponse{event, questions[], polls[]}` |
| POST | `/events/{code}/close` | 진행자 | → 204 |
| GET | `/events/{code}/stream` | **공개** | → `text/event-stream` |
| POST | `/events/{code}/questions` | 로그인 | `{content}` → 201 `QuestionResponse` |
| POST | `/questions/{id}/like` | 로그인 | → `LikeResponse{liked, likeCount}` |
| DELETE | `/questions/{id}` | 진행자 또는 작성자 | → 204 |
| POST | `/events/{code}/polls` | 진행자 | `{title, options[]}` → 201 `PollResponse` |
| POST | `/polls/{id}/votes` | 로그인 | `{optionId}` → `PollResponse` (재투표 409) |
| POST | `/polls/{id}/close` | 진행자 | → 204 |

스냅샷 DTO:
- `QuestionResponse{id, content, authorNickname, likeCount, likedByMe, mine, createdAt}`
- `PollResponse{id, title, status, options[{id, text, count}], totalVotes, myOptionId}`
- 질문 정렬: ZSET 점수 내림차순, 동점은 최신 질문 우선(`createdAt` desc) — Java에서 정렬.

**스냅샷 + 스트림 분리**: 입장·재연결 시 REST 스냅샷으로 현재 상태 전체를 받고, 이후 변화만 SSE로 받는다. `Last-Event-ID` 재전송은 구현하지 않는다(재연결 = 스냅샷 재요청).

## 5. SSE

### 5.1 구성 요소

| 클래스 | 역할 |
|---|---|
| `LiveSseRegistry` | `ConcurrentHashMap<Long, Set<SseEmitter>>` (값은 `ConcurrentHashMap.newKeySet()`). `subscribe(eventId)`, `broadcast(eventId, name, data)`, `completeAll(eventId)`, `heartbeat()` |
| `LiveBroadcaster` | `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)`로 도메인 이벤트를 받아 registry로 전송 |
| `LiveChangedEvent` | `record LiveChangedEvent(Long eventId, String name, Object payload)` — 서비스가 `ApplicationEventPublisher`로 발행 |

- emitter 타임아웃 30분. `onCompletion`/`onTimeout`/`onError`에서 제거.
- 구독 직후 `connected` 이벤트 1회 전송 → 헤더가 즉시 flush되고 클라이언트가 연결 성공을 안다.
- `@Scheduled(fixedRate = 25s)` heartbeat: `SseEmitter.event().comment("ping")`. 전송 실패 emitter는 제거.
- `send` 실패(`IOException`, `IllegalStateException`)는 debug 로그 후 제거 — 끊긴 클라이언트는 정상 상황.
- 컨트롤러는 응답에 `X-Accel-Buffering: no`, `Cache-Control: no-cache`를 붙인다.
- 존재하지 않는 code는 404, CLOSED 이벤트 구독은 `event.closed` 1회 보내고 즉시 complete.

### 5.2 이벤트 목록 (공개 payload — userId·개인 상태 없음)

| name | data |
|---|---|
| `connected` | `{eventId}` |
| `question.created` | `{id, content, authorNickname, likeCount:0, createdAt}` |
| `question.liked` | `{id, likeCount}` |
| `question.deleted` | `{id}` |
| `poll.created` | `{id, title, status, options[{id, text, count}], totalVotes}` |
| `poll.voted` | `{id, options[{id, count}], totalVotes}` |
| `poll.closed` | `{id}` |
| `event.closed` | `{eventId}` |

## 6. Redis

| 키 | 타입 | 내용 |
|---|---|---|
| `live:event:{eventId}:questions` | ZSET | member=questionId, score=좋아요 수 |
| `live:question:{questionId}:likers` | SET | 좋아요 누른 userId |
| `live:poll:{pollId}:counts` | HASH | optionId → 득표수 |
| `live:poll:{pollId}:voters` | HASH | userId → optionId |

`LiveCounterStore` 인터페이스 (단계 15 `RefreshTokenStore` 패턴):

```java
void addQuestion(Long eventId, Long questionId);
void removeQuestion(Long eventId, Long questionId);
LikeResult toggleLike(Long eventId, Long questionId, Long userId);   // record LikeResult(boolean liked, long likeCount)
Map<Long, Long> likeCounts(Long eventId);                             // questionId → count
Set<Long> likedQuestionIds(Long userId, Collection<Long> questionIds);
boolean vote(Long pollId, Long optionId, Long userId);                // false = 이미 투표
Map<Long, Long> voteCounts(Long pollId);                              // optionId → count
Long votedOptionId(Long pollId, Long userId);                         // 없으면 null
```

- `RedisLiveCounterStore`: `StringRedisTemplate` + `DefaultRedisScript<Long>` 2개.
  - like toggle Lua: `SADD likers uid` → 1이면 `ZINCRBY +1`, 0이면 `SREM` + `ZINCRBY -1`. 결과 `{liked, score}`를 한 번의 왕복으로 반환. 모든 키 `EXPIRE 604800`.
  - vote Lua: `HSETNX voters uid optionId` → 1이면 `HINCRBY counts optionId 1` 후 1 반환, 아니면 0. 모든 키 `EXPIRE 604800`.
- `InMemoryLiveCounterStore`(test): `synchronized` 메서드 — 테스트 컨텍스트에서 `@Primary`로 대체.
- `LiveCounterStoreContractTest`: InMemory 구현에 같은 시나리오 적용(기존 `RefreshTokenStoreContractTest`와 동일 구조).
- 한계(문서 명시): Redis 데이터 소실·TTL 만료 시 집계가 0으로 보인다. 영구 보존이 필요하면 이벤트 종료 시 MySQL 스냅샷 저장이 다음 단계.

## 7. 서비스 계층

| 클래스 | 책임 |
|---|---|
| `LiveEventService` | 생성(코드 발급), 내 목록, 스냅샷 조립, 종료(+`event.closed`) |
| `LiveQuestionService` | 등록, 좋아요 토글, 삭제 |
| `LivePollService` | 생성, 투표, 마감 |
| `LiveSecurity` (`@Component("liveSecurity")`) | `isHost(String code, CustomUserDetails)`, `isPollHost(Long pollId, …)`, `canDeleteQuestion(Long questionId, …)` |
| `LiveCodeGenerator` | 참여 코드 생성 (테스트에서 대체 가능하도록 분리) |

공통 규칙: 쓰기 전에 이벤트 OPEN 확인(`LIVE_EVENT_CLOSED` 409), 투표는 `Poll` OPEN 확인(`POLL_CLOSED` 409), `optionId`가 해당 poll 소속인지 확인(`POLL_OPTION_NOT_FOUND` 404). 존재 안 하는 자원은 404, 소유권 실패는 `@PreAuthorize` → 403.

## 8. 보안·예외·인프라

- `SecurityConfig`: `.requestMatchers(HttpMethod.GET, "/api/v1/live/events/*/stream").permitAll()` 추가 (`anyRequest()` 앞).
- `@EnableScheduling`은 `LiveSseConfig`(신규)에 둔다.
- `ErrorCode` 추가: `LIVE_EVENT_NOT_FOUND`, `QUESTION_NOT_FOUND`, `POLL_NOT_FOUND`, `POLL_OPTION_NOT_FOUND`(404), `LIVE_EVENT_CLOSED`, `POLL_CLOSED`, `ALREADY_VOTED`(409). 409는 기존 `BusinessException` 계열로 던진다.
- `frontend/nginx.conf`: `location /api/v1/live/` 블록 — `proxy_buffering off; proxy_cache off; proxy_read_timeout 1h;` + 기존 헤더 4종.
- Vite dev proxy는 기존 `/api` 규칙으로 SSE도 통과(확인 항목).
- 로깅: 구독/해제 debug, 이벤트 생성·종료 info, 전송 실패 debug.

## 9. 프론트 (`board/frontend`)

| 파일 | 내용 |
|---|---|
| `src/liveStream.js` | `openLiveStream(code, handlers)` — `EventSource` 래핑, 이벤트명별 `addEventListener`, `open` 재발생 시 `onReconnect`(스냅샷 재요청), `close()` 반환 |
| `src/components/LiveHub.jsx` | 코드 입력 → 입장, 이벤트 생성, 내가 진행하는 이벤트 목록 |
| `src/components/LiveRoom.jsx` | 질문 입력·목록(좋아요 순), 좋아요 버튼, 투표 카드(결과 막대·내 선택 표시), 진행자 컨트롤(투표 생성·마감, 이벤트 종료) |
| `App.jsx`, `styles.css` | "Live" 탭, All Day A.I 토큰 사용 |

- SSE로 받은 `likeCount`·`counts`는 상태에 병합하고, `likedByMe`·`myOptionId`는 내 REST 응답으로만 갱신.

## 10. 테스트

| 테스트 | 대상 |
|---|---|
| `LiveEventServiceTest` | 생성·코드 충돌 재시도·스냅샷 정렬(점수 desc, 동점 최신)·종료 시 이벤트 발행 |
| `LiveQuestionServiceTest` | 등록·CLOSED 거부·좋아요 토글 발행·삭제 시 Redis 정리 |
| `LivePollServiceTest` | 생성 검증·중복 투표 409·마감 후 409·타 poll 옵션 404 |
| `LiveSseRegistryTest` | 구독·브로드캐스트·실패 emitter 제거·completeAll |
| `LiveCounterStoreContractTest` | toggle/vote 의미론 |
| `SecurityIntegrationTest` 보강 | stream 공개, 쓰기 401, 비진행자 close 403 |

최종 확인: `./scripts/verify.sh`.

## 11. 문서 (`docs/lecture/`)

| 문서 | 내용 |
|---|---|
| `LIVE-POLL.md` | 개념: polling vs SSE vs WebSocket, 스냅샷+스트림, Redis 자료구조 선택, Lua 원자성, 한계와 다음 단계 |
| `LIVE-POLL-WALKTHROUGH-DAY1.md` | 이벤트·질문·좋아요·SSE (백엔드) — curl로 두 터미널 실시간 확인까지 |
| `LIVE-POLL-WALKTHROUGH-DAY2.md` | 투표·집계 → 프론트 → nginx 설정·통합 확인 |

walkthrough 규칙: 일차 → 작업 단위 Step. 각 Step = ① 이 작업을 하는 이유와 의미 ② 낯선 기술 개념 설명 ③ package·import 포함 전체 코드(어려운 줄은 주석) ④ `| 클래스 | 출처 | 역할 |` 표 ⑤ 확인 명령. 아직 만들지 않은 클래스를 앞 Step에서 참조하지 않는다. §0 작업 지도(mermaid + 순서 근거 표), 부록 변경 요약 표. MOC에 단계 18 등록.

## 12. 범위 밖

익명 참여, 다중 인스턴스 전파(Redis Pub/Sub), `Last-Event-ID` 재전송, 질문 모더레이션(숨김·답변 완료 표시), rate limiting, 집계 영구 보존.
