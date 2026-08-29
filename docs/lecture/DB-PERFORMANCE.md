---
tags: [theory, db, performance]
requires: ["[[COMMENT]]", "[[REACTION]]"]
status: 계획
---

# DB 성능 개선 로드맵 — 단계 16 후보 (계획 문서)

> 데이터가 많아질 때(예: 게시글 100만 건) 무엇을 할 수 있는지의 **교육용 로드맵**.
> 아직 구현된 단계가 아니라 설계 전 계획 기록이다. 핵심 교수법은 하나 —
> **"측정 → 개선 → 재측정" 루프를 학생이 직접 돌게 한다.**

---

## 0. 먼저 가르칠 것 — 개발자와 DB 엔지니어의 역할 분담

경계를 한 문장으로: **쿼리를 아는 사람(개발자)과 서버를 아는 사람(DB 엔지니어)의 분업.**

| 영역 | 개발자 | DB 엔지니어(DBA) |
|------|--------|------------------|
| 설계 | 스키마·엔티티·연관관계 | 용량 계획, 스토리지 |
| 쿼리 | 작성·개선(N+1, fetch join), `EXPLAIN` 검증 | slow log **발견**·모니터링 운영 |
| 인덱스 | 쿼리 패턴 기반 **제안** | 쓰기 비용·중복 검토 후 **승인** |
| 운영 | 커넥션 풀, 앱 캐싱(Redis), 트랜잭션 범위 | 파라미터 튜닝, 백업·복구, 리플리카·HA, 권한 |
| DDL | 작성 | 대형 테이블 무중단 반영 |

수업 강조점 두 가지:

- **우리 프로젝트는 두 모자를 다 쓴다** — mysql-8 컨테이너를 직접 띄우고(DBA 일)
  엔티티·쿼리도 직접 짠다(개발자 일). 소규모 팀의 전형.
- **클라우드(managed DB)가 경계를 옮긴다** — RDS류가 백업·리플리카를 흡수하면서
  개발자 몫(쿼리·인덱스·캐싱)의 비중이 커졌다. "그래서 개발자도 EXPLAIN은 읽어야 한다."

아래 §1~5는 전부 **개발자 모자** 쪽 일이고, §6은 DBA 모자 쪽이라 개념만 소개한다.

---

## 1. 더미 데이터 대량 생성 — 모든 실습의 전제

100건으로는 아무것도 안 느리다. 느려지는 것을 먼저 보게 한다.

```sql
-- 재귀 CTE로 boards 100만 건
INSERT INTO boards (title, content, user_id, created_at, updated_at)
WITH RECURSIVE seq AS (
  SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 1000000
)
SELECT CONCAT('제목 ', n), CONCAT('내용 ', n), 1, NOW() - INTERVAL n SECOND, NOW()
FROM seq;
```

---

## 2. 인덱스 — 본론 (효과가 가장 극적)

| 실습 대상                                            | 문제                        | 처방                                         |
| ------------------------------------------------ | ------------------------- | ------------------------------------------ |
| 목록 `ORDER BY created_at DESC`                    | filesort                  | `created_at` 인덱스                           |
| 게시판별 목록 `WHERE board_id = ? ORDER BY created_at` | FK 인덱스로 20만 건을 찾고도 전량 재정렬 | 복합 인덱스 `(board_id, created_at)` — 컬럼 순서 교육 |
| 알림 `WHERE user_id = ? AND is_read = false`       | 단일 인덱스의 한계                | 복합 인덱스 `(user_id, is_read)`                |
| 반응 집계                                            | count 반복                  | covering index 개념                          |

> **정정(실측 확인)**: 초안에는 "JPA는 FK 인덱스를 자동으로 안 만든다"고 썼으나,
> **MySQL(InnoDB)은 FK 제약마다 인덱스를 자동 생성한다** — `SHOW INDEX FROM posts` 로
> 확인된다(`FK78qo1...` 등). 그 통념은 PostgreSQL 쪽 이야기다. 따라서 우리 실습의
> 타깃은 FK 단독 컬럼이 아니라 **정렬·복합 인덱스**다. 상세는 [[DB-PERFORMANCE-LAB]] §7.

- **`EXPLAIN` 전/후 비교가 하이라이트** — `type: ALL`(풀스캔) → `ref`, rows 100만 → 수십.
  MySQL 8의 `EXPLAIN ANALYZE`는 실측 시간까지 보여준다.
- 인덱스는 조회를 빠르게 하는 대신 **모든 쓰기에 세금** — §0 협업(제안↔승인)의 이유.
- `@Table(indexes = @Index(...))` 선언이면 `ddl-auto: update`가 만들어 준다(코드로 스키마 관리).

---

## 3. 깊은 페이지네이션 — OFFSET의 배신

```sql
-- ?page=40000: 80만 건을 "읽고 버린다" (건너뛰는 게 아님)
SELECT ... ORDER BY id DESC LIMIT 20 OFFSET 800000;

-- keyset(cursor): 어느 페이지든 동일 속도
SELECT ... WHERE id < :lastSeenId ORDER BY id DESC LIMIT 20;
```

곁들일 주제: Spring Data `Page` vs `Slice`(count 쿼리 비용), React 무한스크롤 연동.

---

## 4. N+1 재실측 + slow query log

- [[COMMENT]]·[[REACTION]]에서 배운 N+1 해법을 **대량 데이터에서 재실측** — 그때의
  fetch join이 실제 몇 배 차이인지 숫자로 회수한다.
- `slow_query_log` + `long_query_time` — 개선보다 **발견**이 실무에선 먼저다.

---

## 5. Redis 캐시 — 단계 15의 자연스러운 후속

목록 첫 페이지를 TTL 30초 캐시로. [[REDIS-TOKEN]]의 TTL 개념이 토큰→캐시로 재사용된다.
maxmemory-policy가 토큰(noeviction)과 캐시(lru 계열)에서 왜 달라야 하는지도 회수 포인트.

---

## 6. 개념 소개만 (2GB 서버에서 실습 무리 — DBA 영역)

리플리카(읽기 분산)·파티셔닝·샤딩은 그림과 트레이드오프만. 커넥션 풀 사이징은 부차적.

---

## 제안 커리큘럼 (단계 16 구성안)

더미 100만 건 → `EXPLAIN`으로 문제 확인 → 인덱스 → keyset 페이지네이션 → Redis 캐시.
매 절마다 실측 숫자를 남긴다. 진행 확정 시 [[REDIS-TOKEN]]처럼 설계 문서부터 작성한다.

> **실습편 완성**: §1~3을 실제 100만 건으로 수행하는 step-by-step 실습이
> [[DB-PERFORMANCE-LAB]] 에 있다 (전 구간 실측값 포함 — 1,460ms→0.9ms 등).
> 남은 것은 구현편(엔티티 인덱스 선언 + keyset API + Redis 캐시)이다.
