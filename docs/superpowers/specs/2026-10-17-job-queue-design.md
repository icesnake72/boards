---
type: spec
step: 19
tags: [spec, jobs, redis-streams, queue, workbook]
status: approved-design
---

# 단계 19 — 작업 큐(Redis Streams) 실습 설계

> 2026-10-17 확정. 산출물은 **수강생이 직접 코드를 작성하는 실습 가이드**다. 정답 구현은 `answer/job-queue` 브랜치에
> 두고 main에는 merge하지 않는다(main에는 문서만).

## 1. 목표와 성공 기준

| 항목 | 내용 |
|---|---|
| 학습 목표 | (1) 요청은 202로 즉시 응답, 작업은 작업자가 나중에 (2) `XADD`·`XREADGROUP`·`XACK` (3) PEL + `XPENDING`/`XCLAIM`으로 실패 재시도·작업자 다운 복구 (4) 실패 보관함(DLQ), 상태·통계를 API·로그·redis-cli로 확인 |
| 예제 작업 | 큐 전용 데모: `durationSeconds`만큼 일하는 척, 처음 `failTimes`번은 일부러 실패 |
| 성공 기준 | 정답 브랜치에서 `./scripts/verify.sh` 통과, Postman 시나리오 4가지(정상·분배·재시도/DLQ·작업자 다운 복구)가 로그와 함께 재현됨. 워크북만 보고 같은 결과를 낼 수 있음 |

## 2. 결정 사항

| 결정 | 선택 | 이유 |
|---|---|---|
| 큐 | Redis Streams + consumer group | 새 인프라 없음(2GB 서버), ACK·PEL·넘겨받기로 큐 핵심 개념이 모두 드러남 |
| 넘겨받기 | `XPENDING` + `XCLAIM`(min-idle) | Spring Data Redis 3.5.12에 `XAUTOCLAIM` API가 없다. 전달 횟수가 코드에 드러나 교육적. `XAUTOCLAIM`은 redis-cli 실습으로 |
| 상태 저장 | Redis Hash `jobs:{id}` (TTL 1일) | 큐 학습에 집중 — MySQL 엔티티 없음 |
| 시도 횟수 | PEL의 delivery count가 기준, Hash `attempts`는 표시용 | Redis가 직접 세는 값이 정확하다 |
| 작업자 | 앱 하나에 `worker-1`, `worker-2`(설정으로 개수 변경) | 분배를 로그로 확인 |
| 소비자 이름 | 고정(`worker-N`) | 재시작 후 같은 이름 — 이전 PEL은 넘겨받기 담당이 회수(강의 단순화). 다중 서버라면 호스트명을 붙여야 함(문서에 명시) |
| 테스트 컨텍스트 | `app.jobs.enabled=false` → 리스너 컨테이너·재시도 담당 빈 미생성 | 기존 `@SpringBootTest` 수백 개가 Redis 없이 돌아야 한다 |
| 인증 | 기존 규칙 그대로(로그인 필요) | 새 보안 규칙 없음 |

## 3. Redis 키

| 키 | 타입 | 내용 |
|---|---|---|
| `jobs:stream` | Stream | 필드 `jobId`, `durationSeconds`, `failTimes` |
| 그룹 `job-workers` | Consumer group | `XGROUP CREATE jobs:stream job-workers $ MKSTREAM` (이미 있으면 BUSYGROUP 무시) |
| `jobs:{jobId}` | Hash, TTL 1일 | `status`, `attempts`, `worker`, `result`, `error`, `createdAt`, `updatedAt` |
| `jobs:dead` | Stream | 실패 보관함: `jobId`, `originalId`, `deliveries`, `error` |

## 4. 상태 흐름

`QUEUED` →(작업자 수신) `RUNNING` →(성공) `DONE`
`RUNNING` →(실패, ACK 안 함) `RETRY_WAIT` →(minIdle 경과, 재시도 담당이 XCLAIM) `RUNNING` …
전달 횟수 ≥ `maxAttempts`이고 아직 실패면 → `jobs:dead`에 XADD, XACK, `FAILED`

## 5. API (`/api/v1/jobs`, 로그인 필요)

| 메서드 | 경로 | 요청 → 응답 |
|---|---|---|
| POST | `/api/v1/jobs` | `{durationSeconds: 1~60, failTimes: 0~5}` → **202** `JobView` |
| GET | `/api/v1/jobs/{jobId}` | → `JobView` / 없으면 404 `JOB_NOT_FOUND` |
| GET | `/api/v1/jobs/stats` | → `{streamLength, pending, consumers[{name, pending, idleMs}], deadLetters}` |

`JobView{jobId, status, attempts, worker, result, error, createdAt, updatedAt}`

## 6. 구성 요소 (`com.example.board.jobs`)

| 클래스 | 책임 |
|---|---|
| `JobProperties` | `@ConfigurationProperties("app.jobs")`: `enabled`, `stream`, `group`, `deadStream`, `workers`, `maxAttempts`, `minIdle`, `reclaimInterval` |
| `JobStatus` | enum 5개 |
| `JobStore` | 상태 Hash 읽기·쓰기(TTL) |
| `JobProducer` | jobId 발급 → Hash `QUEUED` → `XADD` (생산자) |
| `JobWorker` | 레코드 하나 처리: `RUNNING` → 일하는 척 → 성공이면 `DONE`+`XACK`, 실패면 `RETRY_WAIT`(ACK 안 함) |
| `JobReclaimer` | `@Scheduled`: `XPENDING` → idle ≥ minIdle 항목 중 전달 횟수 ≥ maxAttempts면 DLQ, 아니면 `XCLAIM` 후 `JobWorker`로 재실행 |
| `JobQueueConfig` | `@ConditionalOnProperty(app.jobs.enabled)`: 그룹 생성, `StreamMessageListenerContainer` + 작업자 N개 구독(autoAck 끔), `@EnableScheduling` 재사용 |
| `JobMonitor` | stats: `XLEN`, `XPENDING` 요약, `XINFO CONSUMERS`, DLQ 길이 |
| `JobController`, DTO | REST |

로그: 생산(`[producer] job=… queued id=…`), 수신(`[worker-1] start job=… attempt=…`), 완료/실패, 넘겨받기(`[reclaimer] claimed job=… deliveries=…`), DLQ 이동 — 모두 info, 수강생이 로그로 흐름을 읽을 수 있게.

## 7. 테스트

| 테스트 | 내용 |
|---|---|
| `JobFlowRedisTest` | 실제 Redis(DB 14, `jobs:*` 키만 정리, 없으면 skip): 생산 → 작업자 처리 → DONE·ACK / 실패 → PEL 잔류 / 재시도 담당이 넘겨받아 DONE / maxAttempts 초과 → DLQ·FAILED |
| `JobControllerTest` | `@SpringBootTest` + MockMvc: 202·검증 400·없는 id 404·토큰 없으면 401 (Redis 무관하게 생산자를 대체) |
| 기존 전체 | `app.jobs.enabled=false`로 회귀 없음 |

## 8. 문서 (`docs/lecture/`)

| 문서 | 내용 |
|---|---|
| `JOB-QUEUE-WORKBOOK.md` | 개념(왜 큐, Streams 구조, PEL·ACK·넘겨받기) → Step 0 redis-cli 체험 → Step 1~8(목표·수정 파일·직접 쓸 부분·힌트·확인) → 최종 Postman+로그 시나리오 4가지 → 문제 해결 |
| `JOB-QUEUE-ANSWERS.md` | Step별 정답 전체 코드(정답 브랜치 커밋에서 생성) |
| `000-MOC.md` | 단계 19 등록 |

## 9. 범위 밖

우선순위 큐, 예약 실행, 다중 서버 배포, UI, 운영 배포(강의 실습용 — 필요 시 별도 결정).
