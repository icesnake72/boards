---
step: 17
track: domain
tags: [db, search, fulltext, mysql]
requires: ["[[POST-SEARCH]]", "[[DB-PERFORMANCE-LAB]]"]
status: 완료
---

# 단계 17 실습 — 게시글 100만 건 검색 (FULLTEXT 따라하기)

> **왜 이 단계인가**([[POST-SEARCH]])를 읽고 왔다는 전제의 **손 실습 문서**다.
> 명령어는 전부 **한 박스에 하나씩** — 순서대로 하나 실행하고, 결과를 확인하고,
> 다음으로 넘어간다. 이 문서의 모든 수치는 실제 실행 실측값이다(로컬 도커
> mysql-8, 게시글 1,000,008건 기준).
>
> 소요 시간: 약 20~30분. 필요한 것: [[DB-PERFORMANCE-LAB]] §2의 더미 100만 건이
> 들어 있는 `mysql-8` 컨테이너 (지웠다면 그 문서 §2로 다시 만든다).

---

## 0. 오늘 배울 것 (한 장 요약)

| 배울 것 | 증거 (실측) | 한 줄 원리 |
|---------|-------------|------------|
| LIKE 부분 일치의 실패 | 4,626ms (인덱스 무용) | B-tree는 첫 글자부터의 목차 — `%키워드%`는 못 탄다 |
| FULLTEXT(ngram) | 4,626ms → **7.15ms** (650배) | 토큰→문서 역색인 — "찾아보기" 페이지 |
| 검색 인덱스의 세금 | 생성 26.5초, 크기 69.1MB | B-tree(1.7초)의 15배 — 만들고 유지하는 비용이 크다 |
| 흔한 단어의 최악 케이스 | **10분+ 미완료, 강제 중단** | 모든 문서에 있는 토큰은 역색인도 못 구한다 |
| 실전 함정 4개 | ADD FULLTEXT 버그, charset, 1글자, 크기 은폐 | §3·§5·§7에서 하나씩 재현 |

---

## 1. 준비 — 접속과 상태 확인

mysql 클라이언트로 들어간다. **`--default-character-set=utf8mb4`가 필수다** —
빼먹으면 한글 검색어가 `????`로 깨져 모든 검색이 조용히 0건이 된다(함정 ①,
실제로 이 문서 작성 중에도 처음에 당했다).

```bash
docker exec -it mysql-8 mysql --default-character-set=utf8mb4 -uroot -p1234 board
```

더미가 살아 있는지 확인:

```sql
SELECT COUNT(*) FROM posts;
```

**기대**: 약 100만 건 (실측 1,000,008 — 실습 후 남긴 앱 데이터 포함).

ngram 토큰 크기 확인 (뒤의 §5에서 이 값이 계속 등장한다):

```sql
SELECT @@ngram_token_size;
```

**기대**: `2` (기본값 — 두 글자 단위로 색인한다는 뜻).

---

## 2. 문제 재현 — LIKE 검색은 얼마나 느린가

지금 posts에는 단계 16의 B-tree 인덱스들(`idx_posts_created_at`,
`idx_posts_board_created`)이 있다. **인덱스가 있는데도** 부분 일치 검색이 어떻게
되는지 본다. 키워드는 전체 100만 건 중 **딱 1건**에만 있는 `731942`:

```sql
EXPLAIN ANALYZE SELECT id, title FROM posts
WHERE title LIKE '%731942%' ORDER BY created_at DESC LIMIT 20\G
```

실측 결과:

```
-> Limit: 20 row(s)  (actual time=2779..4626 rows=1)
    -> Filter: (posts.title like '%731942%')  (actual time=2779..4626 rows=1)
        -> Index scan on posts using idx_posts_created_at (reverse)
           (actual time=6.03..4476 rows=1e+6)
```

**4,626ms.** 계획을 읽어 보면 옵티마이저의 도박이 보인다:

1. "최신순 인덱스를 거꾸로 걷다 보면 20건 금방 차겠지"라는 전략을 골랐다
   (단계 16 §5에서 본 그 계획).
2. 그런데 키워드가 희귀해서(1건) **인덱스 100만 건을 끝까지 걷고도** 1건뿐.
3. 흔한 키워드였다면 빨라 보였을 것이다 — **사용자가 뭘 검색하느냐에 따라 성능이
   널뛰는** 구조. 검색 기능에 이런 도박은 못 쓴다.

title과 content를 함께 검색하면(실제 검색 UX):

```sql
EXPLAIN ANALYZE SELECT id, title FROM posts
WHERE title LIKE '%731942%' OR content LIKE '%731942%'
ORDER BY created_at DESC LIMIT 20\G
```

**실측: 4,652ms** — 다를 게 없다. 어떤 B-tree를 더 만들어도 이 숫자는 안 바뀐다.

---

## 3. FULLTEXT 인덱스 생성 — 그리고 첫 번째 함정

### 3-1. 교과서 대로 실행하면 — 실패한다

```sql
ALTER TABLE posts ADD FULLTEXT INDEX ft_posts_title_content (title, content) WITH PARSER ngram;
```

실측 결과 (약 30초 걸려서 실패):

```
ERROR 1062 (23000): Duplicate entry '0-0000-00-00 00:00:00.000000'
for key 'posts.idx_posts_board_created'
```

> 주의: 이 에러를 그대로 믿으면 안 된다. `idx_posts_board_created`는 UNIQUE
> 인덱스가 아니라서 duplicate 에러가 **날 수 없는** 인덱스다. 이것은 MySQL
> 8.0.33의 알려진 결함 — 첫 FULLTEXT 추가는 숨은 `FTS_DOC_ID` 컬럼 때문에 테이블
> 리빌드를 유발하는데, 그 리빌드(병렬 인덱스 빌드)가 기존 복합 인덱스와 충돌하며
> 엉뚱한 에러를 낸다. `SET SESSION innodb_ddl_threads = 1`(병렬 끄기)로도 실측
> 결과 해결되지 않았다.

### 3-2. 우회 — 복사 방식(ALGORITHM=COPY)으로 명시

```sql
ALTER TABLE posts ADD FULLTEXT INDEX ft_posts_title_content (title, content) WITH PARSER ngram, ALGORITHM=COPY;
```

**실측: 26.5초 — 성공.** `ALGORITHM=COPY`는 "테이블 전체를 새로 복사하며 만드는"
구식 방식이라 결함 있는 최적화 경로를 아예 타지 않는다. 대신 복사 중 쓰기가
막히므로, 운영 서버라면 [[DB-PERFORMANCE]] §0의 역할 분담대로 "제안 → 검토"를
거쳐 한가한 시간에 반영할 일이다.

비교해 둘 것 — 단계 16의 B-tree 생성은 1.7초였다. **검색 인덱스는 15배 비싸다**
(모든 행의 title+content를 2글자 토큰으로 전부 분해해 역색인을 만들어야 하므로).

생성 확인:

```sql
SHOW INDEX FROM posts WHERE Key_name = 'ft_posts_title_content';
```

**기대**: `Index_type = FULLTEXT` 인 행 2개 (title, content).

---

## 4. MATCH AGAINST — 재측정

§2와 같은 검색을 검색 전용 문법으로:

```sql
EXPLAIN ANALYZE SELECT id, title FROM posts
WHERE MATCH(title, content) AGAINST('731942' IN BOOLEAN MODE)
ORDER BY created_at DESC LIMIT 20\G
```

실측 결과:

```
-> Limit: 20 row(s)  (actual time=7.15..7.15 rows=1)
    -> Sort row IDs: posts.created_at DESC, limit input to 20 row(s) per chunk
        -> Filter: (match posts.title,posts.content against ('731942' in boolean mode))
            -> Full-text index search on posts using ft_posts_title_content
               (actual time=6.99..7 rows=1)
```

**4,626ms → 7.15ms. 650배.** 계획에서 확인할 것:

- `Index scan ... rows=1e+6` 이 사라지고 `Full-text index search ... rows=1` —
  역색인이 "731942가 든 문서"를 **바로** 찾았다. 100만 건을 걷지 않는다.
- 그 뒤의 `Sort row IDs`는 찾아낸 결과(1건)만 정렬 — 대상이 작으니 공짜나 다름없다.

---

## 5. ngram 관찰 — 검색 인덱스의 성격 알기

### 5-1. 1글자 검색은 항상 0건

```sql
SELECT COUNT(*) FROM posts WHERE MATCH(title, content) AGAINST('성' IN BOOLEAN MODE);
```

**실측: 0건.** 모든 제목이 `성능실습…`으로 시작하는데도 0건이다 —
`ngram_token_size=2`라 색인에 **2글자 토큰만** 존재하고, 1글자 `성`은 대조할
토큰 자체가 없다. 구현편에서 API가 **2글자 미만 검색어를 400으로 거부**해야
하는 이유(조용한 0건보다 명시적 거부가 낫다).

### 5-2. BOOLEAN 연산자 — 조건 조합

```sql
SELECT COUNT(*) FROM posts WHERE MATCH(title, content) AGAINST('+성능 +731942' IN BOOLEAN MODE);
```

**실측: 1건, 즉시 응답.** `+`는 "반드시 포함". 흥미로운 점 — `성능`은 100만 건
전부에 있는 흔한 토큰인데도 빨랐다. 교집합 연산이 **희귀한 쪽(`731942`) 결과부터**
좁혀 들어가기 때문이다. (흔한 토큰 **단독** 검색이 어떻게 되는지는 §6에서.)

### 5-3. 관련도 점수 — ngram의 느슨한 매칭

```sql
SELECT id, title, ROUND(MATCH(title, content) AGAINST('실습 731942'), 2) AS score
FROM posts WHERE MATCH(title, content) AGAINST('실습 731942') LIMIT 3;
```

실측 결과:

```
1780512  성능실습731942   16.99   ← 정확 일치가 최고점
1080512  성능실습31942    13.59   ← 부분 겹침도 매칭됨
1121764  성능실습73194    13.59
```

NATURAL LANGUAGE 모드(연산자 없는 기본)는 관련도순으로 반환한다. 주목할 것은
2·3위 — `31942`, `73194`는 검색어와 **다른 숫자인데 검색됐다.** 검색어 `731942`가
2글자 토큰 `73/31/19/94/42`로 쪼개지고, 그 토큰 일부를 공유하는 문서도 (낮은
점수로) 걸리기 때문이다. ngram 검색이 "정확 포함"이 아니라 **토큰 겹침 기반의
느슨한 매칭**임을 보여준다 — 정확 포함이 필요하면 BOOLEAN 모드의 구문 검색
`AGAINST('"731942"' IN BOOLEAN MODE)`을 쓴다.

---

## 6. 흔한 단어의 최악 케이스 — 직접 실행하지 말 것

> [!WARNING]
> 이 절의 쿼리는 **실행하지 말 것.** 아래는 문서 작성 중 대신 실행한 기록이다 —
> 로컬에서 따라 하면 10분 이상 MySQL이 CPU 100%로 잠기고, 중단도 잘 안 된다.

100만 건 **전부**에 들어 있는 토큰 `성능실습`을 검색하면:

```sql
-- 실행 금지 (기록용)
SELECT id, title FROM posts
WHERE MATCH(title, content) AGAINST('성능실습' IN BOOLEAN MODE)
ORDER BY created_at DESC LIMIT 20;
```

실측 기록:

| 시각 | 상황 |
|------|------|
| 0초 | 실행 시작 — CPU 100%, 메모리 3GB+ 사용 |
| 609초 | `KILL QUERY`로 중단 시도 — **무시됨** |
| 774초 | processlist 상태 `Killed / FULLTEXT initialization` 그대로 진행 중 |
| 약 13분 | 커넥션 KILL 후에야 종료 |

배울 것 두 가지:

1. **역색인도 흔한 토큰은 못 구한다** — "성능실습이 든 문서 목록"이 100만 건
   전체라서, 그 목록을 만드는 것 자체가 일이 된다. LIMIT 20은 그 다음 단계라
   도움이 안 된다. 검색엔진들이 영어의 the/a 같은 단어를 색인에서 빼는
   (stopword) 이유가 이것이다.
2. **FULLTEXT 초기화 단계는 KILL이 잘 안 듣는다** — `KILL QUERY`가 무시되고
   상태가 `FULLTEXT initialization`에 머무는 것을 실측으로 확인했다. 운영에서
   이런 쿼리가 유입되면 "죽일 수도 없는" 부하가 된다 — 구현편에서 검색어 검증
   (최소 길이·금칙 처리)이 방어선이 되는 이유.

---

## 7. 크기 관찰 — 검색 인덱스는 어디에 숨어 있나

단계 16 §4에서 쓴 용량 쿼리를 다시:

```sql
SELECT ROUND(DATA_LENGTH/1024/1024,1) AS data_mb, ROUND(INDEX_LENGTH/1024/1024,1) AS index_mb
FROM information_schema.TABLES WHERE TABLE_SCHEMA='board' AND TABLE_NAME='posts';
```

**실측: `index_mb = 69.7` — FULLTEXT를 만들기 전과 동일하다(!).** 69MB짜리
인덱스가 사라진 게 아니라, FULLTEXT는 **별도의 보조 테이블(FTS_*)에 저장**되어
`INDEX_LENGTH`에 집계되지 않는다. 진짜 크기는 이렇게 본다:

```sql
SELECT ROUND(SUM(FILE_SIZE)/1024/1024,1) AS fts_mb
FROM information_schema.INNODB_TABLESPACES WHERE NAME LIKE '%fts%';
```

**실측: 69.1MB** — 데이터 본체(104.6MB)의 66%. 제목+내용을 2글자씩 겹쳐 쪼갠
토큰을 전부 저장하니 클 수밖에 없다. "용량 모니터링에서 FULLTEXT는 따로 봐야
한다"는 실무 지식이 이 두 쿼리의 차이에 들어 있다.

---

## 8. 뒷정리 — 실습 전 상태로 복원

> 주의: 단계 17 구현편을 바로 이어서 할 계획이면 인덱스를 남겨 둬도 된다.
> 여기서는 "완전 복원"의 정석 절차를 기록한다.

인덱스 삭제:

```sql
ALTER TABLE posts DROP INDEX ft_posts_title_content;
```

**실측: 0.8초** (생성 26.5초와 비교 — 역색인은 버리는 건 싸다). 그런데 다 지워진
게 아니다:

```sql
SELECT NAME FROM information_schema.INNODB_TABLESPACES WHERE NAME LIKE '%fts%';
```

**실측: `fts_..._config`, `fts_..._deleted` 등 보조 테이블 5개(각 0.1MB)가 남는다.**
숨은 `FTS_DOC_ID` 관리용 잔재로, DROP INDEX만으로는 사라지지 않는다. 완전 제거는
테이블 리빌드:

```sql
OPTIMIZE TABLE posts;
```

**실측: 6.7초** (`Table does not support optimize, doing recreate + analyze
instead` 라는 note는 정상 — InnoDB는 OPTIMIZE 요청을 재생성으로 수행한다).

최종 확인:

```sql
SELECT COUNT(*) FROM information_schema.INNODB_TABLESPACES WHERE NAME LIKE '%fts%';
```

**기대: 0.** posts 건수도 그대로인지 확인하고 마친다:

```sql
SELECT COUNT(*) FROM posts;
```

**기대: 실습 시작 때와 동일 (실측 1,000,008 — 데이터 무손상).**

---

## 9. 정리 — 오늘의 숫자들

| 항목 | before | after | 배율/비고 |
|------|--------|-------|-----------|
| 희귀 키워드 검색 | 4,626ms (LIKE) | 7.15ms (MATCH) | **650배** |
| 인덱스 생성 | B-tree 1.7초 (단계 16) | FULLTEXT 26.5초 | 15배 비쌈 + 8.0.33 버그 우회 필요 |
| 인덱스 크기 | B-tree 합 69.7MB | FULLTEXT 69.1MB **별도** | INDEX_LENGTH에 안 보임 |
| 1글자 검색 | — | 0건 | ngram_token_size=2 미만은 색인에 없음 |
| 흔한 단어 검색 | — | 10분+ 미완료 | KILL도 안 듣는 최악 케이스 |

**다음(단계 17 구현편)**: 구현 완료 — 작업 순서별 기록은
[[POST-SEARCH-WALKTHROUGH]] (native keyset 쿼리·검색어 정제 3단계·React 검색창,
그리고 검증 중 §6의 병리 케이스를 실제로 밟은 사건 기록까지).
