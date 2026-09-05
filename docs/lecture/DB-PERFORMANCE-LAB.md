---
step: 16
track: domain
tags: [db, performance, mysql]
requires: ["[[DB-PERFORMANCE]]", "[[COMMENT]]"]
status: 완료
---

# 단계 16 실습 — 게시글 100만 건으로 배우는 DB 성능 (따라하기)

> **왜 이 단계인가**([[DB-PERFORMANCE]])를 읽고 왔다는 전제의 **손 실습 문서**다.
> 명령어는 전부 **한 박스에 하나씩** — 순서대로 하나 실행하고, 결과를 확인하고,
> 다음으로 넘어간다. 이 문서의 모든 수치는 실제 실행 실측값이다(2GB급 로컬 도커
> mysql-8 기준 — 환경에 따라 다르지만 **배율의 크기**는 어디서나 재현된다).
>
> 소요 시간: 약 30~40분. 필요한 것: 도커로 떠 있는 `mysql-8` 컨테이너뿐.

---

## 0. 오늘 배울 것 (한 장 요약)

| 실험 | 개선 전 (실측) | 개선 후 (실측) | 배율 |
|------|---------------|---------------|------|
| 최신글 20건 정렬 조회 | 1,460 ms | 0.9 ms | 약 1,500배 |
| 게시판별 최신글 조회 | 1,226 ms | 3 ms | 약 400배 |
| 4만 페이지째 조회 (OFFSET) | 353 ms | 0.07 ms (keyset) | 약 5,000배 |

비결은 단 두 가지 — **인덱스**와 **페이지네이션 방식**. 순서대로 직접 만들어 본다.

---

## 1. 준비 — MySQL에 들어가기

**[명령 1]** mysql-8 컨테이너가 떠 있는지 확인한다.

```bash
docker ps --filter name=mysql-8
```

`Up ...` 이 보이면 준비 완료.

**[명령 2]** MySQL 대화창(클라이언트)에 들어간다. (비밀번호는 강의 설정값 `1234`)

```bash
docker exec -it mysql-8 mysql -uroot -p1234 board
```

프롬프트가 `mysql>` 로 바뀐다. **이후 §7까지의 명령은 모두 이 안에서 실행한다.**

**[명령 3]** 현재 게시글 수를 확인한다.

```sql
SELECT COUNT(*) FROM posts;
```

한 자릿수(수업 중 만든 글 몇 건)가 나올 것이다. 이 정도로는 아무것도 느리지 않다 —
그래서 지금부터 100만 건을 만든다.

---

## 2. 더미 데이터 100만 건 만들기

**[명령 4]** MySQL의 "반복 깊이 제한"을 풀어 준다. (기본 1,000 → 100만)

```sql
SET SESSION cte_max_recursion_depth = 1000000;
```

> 재귀 CTE는 "1부터 N까지 세는 반복문"인데, 무한 루프 사고를 막으려고 기본
> 1,000번까지만 허용된다. 이 설정은 **지금 접속(세션)에서만** 유효하다.

**[명령 5]** 100만 건을 넣는다. **1~2분 걸린다** — 끝날 때까지 기다리자.

```sql
INSERT INTO posts (title, content, view_count, user_id, board_id, created_at, updated_at)
WITH RECURSIVE seq AS (
  SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 1000000
)
SELECT CONCAT('성능실습 ', n), CONCAT('더미 내용 ', n), 0, 1, 1 + (n MOD 5),
       NOW(6) - INTERVAL n SECOND, NOW(6)
FROM seq;
```

읽는 법:
- `seq` = 1, 2, 3, … 1,000,000 을 만들어 내는 재귀 CTE(가상의 번호표)
- 제목은 `성능실습 1` ~ `성능실습 1000000` — **나중에 이 접두어로 안전하게 지운다**
- `1 + (n MOD 5)` — 게시판 1~5번에 골고루 분산 (§5 실습용)
- `NOW(6) - INTERVAL n SECOND` — 작성 시각을 1초씩 과거로 — 정렬 실습용
- `user_id = 1` — 1번 사용자 글로 몰아넣는다 (없다면 회원가입 1회 먼저)

**[명령 6]** 잘 들어갔는지 확인한다.

```sql
SELECT COUNT(*) FROM posts;
```

기대 결과: `1000000` + 원래 있던 몇 건.

---

## 3. 느림을 목격하기 — "최신글 20건"이 왜 1.5초?

**[명령 7]** 게시판 첫 화면이 실행할 법한 쿼리 — 최신순 20건.

```sql
SELECT id, title, created_at FROM posts ORDER BY created_at DESC LIMIT 20;
```

결과 아래 실행 시간을 보자. **실측 1.46초** — 목록 한 번에 1.5초면 서비스가 아니다.

"20건만 달라는데 왜?" — 답은 MySQL에게 직접 물어볼 수 있다.

**[명령 8]** `EXPLAIN ANALYZE` — 실행 계획과 실제 소요를 함께 보여 달라.

```sql
EXPLAIN ANALYZE SELECT id, title, created_at FROM posts ORDER BY created_at DESC LIMIT 20;
```

실측 출력(요약):

```
-> Limit: 20 row(s)                        (actual time=1460..1460 rows=20)
    -> Sort: posts.created_at DESC ...     (actual time=1460..1460 rows=20)
        -> Table scan on posts             (actual time=6.05..1244 rows=1e+6)
```

아래에서 위로 읽는다 (안쪽이 먼저 실행):

| 줄 | 뜻 | 문제 |
|----|-----|------|
| `Table scan on posts ... rows=1e+6` | **테이블 전체(100만 건)를 다 읽음** | 20건 주려고 100만 건을 읽었다 |
| `Sort: created_at DESC` | 읽은 것을 **전부 정렬** | 정렬도 100만 건 대상 |
| `Limit: 20` | 다 끝난 뒤에야 20건만 남김 | 낭비의 완성 |

> **비유**: "가장 최근에 꽂은 책 20권 주세요"라는 부탁에, 도서관 100만 권을
> **전부 꺼내 날짜순으로 다시 꽂은 뒤** 앞의 20권을 주는 격이다.

---

## 4. 인덱스 — 100만 권 책의 "날짜순 목차"

인덱스는 특정 컬럼 순서로 **미리 정렬해 둔 목차**다. 목차가 있으면 "날짜순 앞에서
20권"은 목차의 끝에서 20개만 읽으면 끝난다.

**[명령 9]** `created_at` 목차(인덱스)를 만든다. (100만 건 기준 **실측 1.7초** 소요)

```sql
ALTER TABLE posts ADD INDEX idx_posts_created_at (created_at);
```

**[명령 10]** 같은 쿼리를 다시 실행한다.

```sql
SELECT id, title, created_at FROM posts ORDER BY created_at DESC LIMIT 20;
```

**실측 0.9ms** — 방금 전 1.46초에서 **약 1,500배** 빨라졌다.

**[명령 11]** MySQL이 정말 목차를 썼는지 확인한다.

```sql
EXPLAIN ANALYZE SELECT id, title, created_at FROM posts ORDER BY created_at DESC LIMIT 20;
```

실측 출력(요약):

```
-> Limit: 20 row(s)                                        (actual time=0.93 rows=20)
    -> Index scan on posts using idx_posts_created_at (reverse)  (rows=20)
```

달라진 것: `Table scan`(100만)과 `Sort`가 **사라지고**, `Index scan ... (reverse)`
가 **딱 20건**만 읽었다. 인덱스가 이미 정렬돼 있어 뒤에서부터 20개 걷어오면 끝.

> [!NOTE]
> **인덱스는 공짜가 아니다** — 목차가 생기면 책을 꽂을 때마다(INSERT/UPDATE) 목차도
> 고쳐야 한다. 모든 쓰기에 세금이 붙는 것. "조회 패턴이 확인된 컬럼에만 만든다"가
> 원칙이고, 이것이 [[DB-PERFORMANCE]] §0의 개발자(제안)↔DBA(승인) 협업 지점이다.

---

## 5. 복합 인덱스 — 우리 실제 API의 쿼리를 구하다

우리 목록 API는 사실 전체가 아니라 **게시판별** 조회다
(`PostRepository.findByBoardId(boardId, pageable)` — WHERE + ORDER BY 조합).

**[명령 12]** 3번 게시판의 최신글 — 실제 API가 만드는 쿼리 형태.

```sql
SELECT id, title FROM posts WHERE board_id = 3 ORDER BY created_at DESC LIMIT 20;
```

**실측 1.23초.** 어? §4에서 인덱스를 만들었는데 왜 또 느릴까.

**[명령 13]** 이유를 확인한다.

```sql
EXPLAIN ANALYZE SELECT id, title FROM posts WHERE board_id = 3 ORDER BY created_at DESC LIMIT 20;
```

실측 출력(요약):

```
-> Limit: 20 row(s)                                     (actual time=1226 rows=20)
    -> Sort: posts.created_at DESC ...                  (rows=20)
        -> Index lookup on posts using FK78... (board_id=3)   (rows=200000)
```

- `board_id` 인덱스(FK 제약이 자동으로 만든 것)로 3번 게시판 글 **20만 건**을 찾긴 했는데
- 그 20만 건이 **날짜순은 아니라서** 또 전부 정렬했다

조건(`WHERE board_id`)과 정렬(`ORDER BY created_at`)을 **한 목차**가 담당해야 한다.
그게 **복합 인덱스** — "게시판별로 먼저 묶고, 그 안에서 날짜순" 목차다.

**[명령 14]** 복합 인덱스를 만든다. **컬럼 순서가 핵심** — 조건 컬럼이 앞, 정렬 컬럼이 뒤.

```sql
ALTER TABLE posts ADD INDEX idx_posts_board_created (board_id, created_at);
```

**[명령 15]** 다시 실행한다.

```sql
EXPLAIN ANALYZE SELECT id, title FROM posts WHERE board_id = 3 ORDER BY created_at DESC LIMIT 20;
```

실측 출력(요약):

```
-> Limit: 20 row(s)                                                  (actual time=3.1 rows=20)
    -> Index lookup on posts using idx_posts_board_created (board_id=3) (reverse)  (rows=20)
```

**1,226ms → 3ms.** `Sort` 가 사라지고 읽은 행이 20만 → **20건**이 됐다.

> [!NOTE]
> `(created_at, board_id)` 로 순서를 바꾸면 효과가 없다 — 목차가 "날짜순 → 게시판"
> 이면 특정 게시판만 골라 읽을 수 없기 때문. **"동등 조건 컬럼 먼저, 정렬 컬럼
> 나중"** 이 복합 인덱스의 제1규칙이다.

---

## 6. 깊은 페이지네이션 — OFFSET의 배신과 keyset

**[명령 16]** 사용자가 4만 페이지째(페이지당 20건)를 눌렀다고 하자.

```sql
EXPLAIN ANALYZE SELECT id, title FROM posts ORDER BY id DESC LIMIT 20 OFFSET 800000;
```

실측 출력(요약):

```
-> Limit/Offset: 20/800000 row(s)                    (actual time=353 rows=20)
    -> Index scan on posts using PRIMARY (reverse)   (rows=800020)
```

**353ms**, 읽은 행 **800,020**. OFFSET은 "80만 건 건너뛰기"가 아니라
**"80만 건을 읽고 나서 버리기"** 다. 페이지가 깊을수록 정비례로 느려진다.

**[명령 17]** keyset(cursor) 방식 — "지난 페이지 마지막 글 id보다 작은 것 20건".

```sql
EXPLAIN ANALYZE SELECT id, title FROM posts WHERE id < 200020 ORDER BY id DESC LIMIT 20;
```

실측 출력(요약):

```
-> Limit: 20 row(s)                                              (actual time=0.07 rows=20)
    -> Index range scan on posts using PRIMARY over (id < 200020) (reverse)  (rows=20)
```

**0.07ms** — 읽은 행 20건. 1페이지든 4만 페이지든 **같은 속도**다.
PK 목차에서 `id < 200020` 지점을 바로 찾아 20개만 걷기 때문이다.

> 주의: 이 `200020`은 "id가 1부터 빈틈없이 100만까지"라는 가정의 예시값이다.
> 실제 테이블의 id는 연속이 아니다(AUTO_INCREMENT 시작점·대량 INSERT의 2ⁿ 번호
> 예약·삭제 갭). 그래서 keyset의 커서는 **산수로 계산하지 않고, 직전 페이지
> 응답의 마지막 행 값을 그대로 되돌려 보낸다** — OFFSET 결과와 이 쿼리 결과가
> 다르게 보였다면 그 갭 때문이다.

| | OFFSET 방식 | keyset 방식 |
|---|---|---|
| 요청 | `?page=40000` | `?lastId=200020` |
| 읽는 행 | 80만 + 20 | **20** |
| 깊이별 속도 | 깊을수록 느려짐 | 항상 일정 |
| 단점 | — | "37페이지로 점프" 불가(다음/이전만) — 무한스크롤과 찰떡 |

> API 레벨 전환(응답에 `lastId`를 실어 주고 React 무한스크롤 연동)은 단계 16
> **구현편**([[DB-PERFORMANCE-WALKTHROUGH]])에서 완료했다 — 이 실습은 "왜 바꿔야
> 하는지"의 증거를 확보하는 자리다.

### 6-1. 실전 후일담 — 조인이 낀 deep offset은 더 참혹하다 (그리고 지연 조인)

페이지 점프 UI를 붙이고 나서 실제 API로 **10,001페이지**(OFFSET 200,000)를 눌러
보니 브라우저 기준 3.2초가 걸렸다. 위 [명령 16]의 353ms보다 훨씬 나쁘다 — 왜냐하면
실제 API는 목록에 작성자 이름이 필요해서 **조인(@EntityGraph)** 을 끌고 다니기
때문이다.

**[명령 17-1]** 실제 API와 같은 모양(조인 포함)의 deep offset:

```sql
EXPLAIN ANALYZE SELECT p.id, p.title, u.username
FROM posts p JOIN users u ON u.id=p.user_id JOIN boards b ON b.id=p.board_id
WHERE p.board_id=1 ORDER BY p.created_at DESC LIMIT 20 OFFSET 200000;
```

실측 출력(요약):

```
-> Limit/Offset: 20/200000 row(s)                       (actual time=5126 rows=7)
    -> Nested loop inner join                           (rows=200007)
        -> Index lookup (board_id=1) (reverse)          (rows=200007)
        -> users PRIMARY lookup                         (loops=200007)   ← 조인 20만 번!
```

**5,126ms.** OFFSET이 버릴 20만 행에 대해 **행 읽기 + users 조인까지 전부 해 준
다음** 버린다. N+1을 막으려고 넣은 조인이 deep offset과 만나면 낭비를 20만 배로
증폭시키는 것이다.

**[명령 17-2]** 처방 — **지연 조인(late row lookup)**: "id만 목차(커버링 인덱스)로
뽑고, 조인은 살아남은 20건에만".

```sql
EXPLAIN ANALYZE SELECT p.id, p.title, u.username
FROM (SELECT id FROM posts WHERE board_id=1
      ORDER BY created_at DESC LIMIT 20 OFFSET 200000) t
JOIN posts p ON p.id=t.id JOIN users u ON u.id=p.user_id;
```

실측 출력(요약):

```
-> Nested loop inner join                               (actual time=66.1 rows=7)
    -> Covering index lookup using idx_posts_board_created (board_id=1) (reverse)
       → Limit/Offset: 20/200000                        (rows=200007, 64.9ms)
    -> posts/users PRIMARY lookup                       (loops=7)        ← 조인은 7번만
```

**5,126ms → 66ms (78배).** 20만 엔트리를 지나가는 건 같지만, 인덱스
`(board_id, created_at)`에는 PK(id)가 딸려 있어 **테이블을 한 번도 안 건드리고**
(Covering index) id만 뽑는다. 행 읽기와 조인은 최종 20건에만 일어난다.

| | 조인 끌고 offset | 지연 조인 |
|---|---|---|
| 20만 엔트리 통과 | 행 읽기 + 조인 200,007회 | 커버링 인덱스만 |
| 조인 횟수 | 200,007 | **7** |
| 실측 | 5,126ms | **66ms** |

남는 한계도 알고 가자 — 지연 조인도 인덱스 20만 엔트리를 걷는 **O(깊이)** 라서,
데이터가 100배가 되면 다시 수 초가 된다. "임의 페이지 정확 점프"는 본질적으로 그
앞을 전부 세는 연산이다. 그래서 실서비스는 점프 범위를 제한하거나(구글 검색도
수백 페이지 이상 못 간다), "2026년 8월로 이동" 같은 **날짜 점프(keyset seek,
O(log n))** 로 축을 바꾼다.

> JPA 적용(id 조회 → IN 로딩 2단계)은 [[DB-PERFORMANCE-WALKTHROUGH]] 작업 9 참조.

---

## 7. 알아두면 좋은 관찰 두 가지

**[명령 18]** posts의 인덱스 목록을 보자.

```sql
SHOW INDEX FROM posts;
```

`FK5lidm...(user_id)`, `FK78qo1...(board_id)` 같은 낯선 이름들이 보인다 —
**InnoDB가 FK 제약마다 자동으로 만든 인덱스**다. 즉 MySQL에서는 "FK 컬럼 단독
조회"는 이미 커버되어 있고, 우리가 만들 것은 §4·§5 같은 **정렬·복합** 목차다.
(PostgreSQL은 FK 인덱스를 자동으로 만들지 않는다 — DB마다 다르다는 것도 교훈)

**[함정 체험]** 복합 인덱스를 지우려 하면:

```sql
ALTER TABLE posts DROP INDEX idx_posts_board_created;
```

환경에 따라 이런 에러가 날 수 있다(실측에서 발생):

```
ERROR 1553: Cannot drop index 'idx_posts_board_created': needed in a foreign key constraint
```

복합 인덱스의 첫 컬럼이 `board_id` 라서, MySQL이 **FK를 받치는 인덱스로 재활용**
했기 때문이다 (그 사이 자동 FK 인덱스는 정리됨). 지우려면 단독 인덱스를 먼저
되살려 FK가 옮겨 탈 곳을 만들어 준다 — §8에서 그렇게 한다.

---

## 8. 원상복구 — 실습 흔적 지우기

**[명령 19]** 더미 100만 건 삭제. **오래 걸린다 — 실측 85초.**

```sql
DELETE FROM posts WHERE title LIKE '성능실습 %';
```

> 삭제가 INSERT보다 느린 것도 배울 점이다 — 행마다 인덱스 정리 + 언두 로그 기록.
> 운영에서 대량 삭제는 `LIMIT` 배치로 쪼개거나, 전체 비우기면 `TRUNCATE`(즉시)를 쓴다.

**[명령 20]** FK가 옮겨 탈 단독 인덱스를 먼저 만들고 —

```sql
ALTER TABLE posts ADD INDEX FK78qo1gxd85rcxqojt2cpcmuj6 (board_id);
```

**[명령 21]** 실습용 인덱스 두 개를 지운다.

```sql
ALTER TABLE posts DROP INDEX idx_posts_board_created;
```

```sql
ALTER TABLE posts DROP INDEX idx_posts_created_at;
```

**[명령 22]** 원래대로 돌아왔는지 확인하고 나간다.

```sql
SELECT COUNT(*) FROM posts;
```

```sql
exit
```

---

## 9. 정리 — 오늘 확보한 증거

| 배운 것 | 증거 (실측) | 한 줄 원리 |
|---------|-------------|------------|
| 인덱스 | 1,460ms → 0.9ms | 미리 정렬된 목차 — 20건만 읽는다 |
| 복합 인덱스 | 1,226ms → 3ms | 조건 먼저, 정렬 나중 — 한 목차가 둘 다 담당 |
| keyset | 353ms → 0.07ms | 건너뛰지 말고 지점부터 읽기 |
| 쓰기 세금 | 인덱스 생성 1.7초, 삭제 85초 | 목차는 유지 비용이 있다 |
| DB별 차이 | InnoDB의 FK 자동 인덱스 | "안다"고 넘기지 말고 `SHOW INDEX`로 확인 |

**다음(단계 16 구현편)**: 이 증거를 코드로 옮긴다 — 엔티티에
`@Table(indexes = @Index(...))` 로 인덱스를 영구 선언하고, keyset 방식의
목록 API(+React 무한스크롤)를 구현한다. 서버(운영 DB) 적용 전에는
[[DB-PERFORMANCE]] §0의 원칙대로 "제안 → 검토" 절차를 밟는다.
구현편은 완료되었다 — 작업 순서별 기록은 [[DB-PERFORMANCE-WALKTHROUGH]].
