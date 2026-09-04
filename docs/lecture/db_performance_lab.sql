SELECT * FROM board.posts;

use board;

-- 현재 접속에 한해 
-- cte_max_recursion_depth(재귀 쿼리 종료 조건을 강제로 끊어주는 최대 재귀 횟수 상한) 시스템 변수
set session cte_max_recursion_depth = 1000000;

-- dummy data 100만건 만들기
-- insert into ~ select : select가 만들어낸 행들을 그대로 posts에 붓는 형태이고, 
-- 그 select의 재료가 재귀 cte(Common Table Expression)이다
/*
- seq = 1, 2, 3, … 1,000,000 을 만들어 내는 재귀 CTE(가상의 번호표)
- 제목은 성능실습 1 ~ 성능실습 1000000 — 나중에 이 접두어로 안전하게 지운다
- 1 + (n MOD 5) — 게시판 1~5번에 골고루 분산 (§5 실습용)
- NOW(6) - INTERVAL n SECOND — 작성 시각을 1초씩 과거로 — 정렬 실습용
- user_id = 1 — 1번 사용자 글로 몰아넣는다 (없다면 회원가입 1회 먼저)
*/
insert into posts(title, content, view_count, user_id, board_id, created_at, updated_at)
with recursive seq as (
	select 1 as n union all select n + 1 from seq where n < 1000000
)
select concat('성능실습', n), concat('더미 내용', n), 0, 1, 1 + (n Mod 5), now(6)-interval n second, now(6)
from seq;

select * from boards;

select count(*) from posts;


select id, title, created_at from posts order by created_at desc limit 20;


EXPLAIN ANALYZE SELECT id, title, created_at FROM posts ORDER BY created_at DESC LIMIT 20;
/*
-> Table scan on posts  (cost=101103 rows=994290) (actual time=0.419..270 rows=1e+6 loops=1)\n'
테이블 전체(100만 건)를 다 읽음 -> 20건 주려고 100만건을 다 읽음

-> Sort: posts.created_at DESC, limit input to 20 row(s) per chunk  (cost=101103 rows=994290) (actual time=377..377 rows=20 loops=1)\n        
읽은거 전부 정렬 -> 정렬도 100만건 대상

-> Limit: 20 row(s)  (cost=101103 rows=20) (actual time=377..377 rows=20 loops=1)\n    
다 끝난뒤 20건만 남김 
*/

-- 인덱스 조회하기
show index from posts;

SELECT table_name,                                                                                                                                      
    ROUND(DATA_LENGTH / 1024 / 1024, 1)  AS data_mb,    -- 행 데이터(클러스터드 인덱스)                                                                
    ROUND(INDEX_LENGTH / 1024 / 1024, 1) AS index_mb,   -- 세컨더리 인덱스 전체 합                                                                     
    ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 1) AS total_mb,                                                                                  
    TABLE_ROWS AS approx_rows                                                                                                                          
  FROM information_schema.TABLES                                                                                                                       
  WHERE TABLE_SCHEMA = 'board' AND TABLE_NAME = 'posts';

-- created_at으로 목차(인덱스)를 생성한다.
ALTER TABLE posts ADD INDEX idx_posts_created_at (created_at);


select id, title, created_at from posts order by created_at desc limit 20;

EXPLAIN ANALYZE SELECT id, title, created_at FROM posts ORDER BY created_at DESC LIMIT 20;
/*
-> Index scan on posts using idx_posts_created_at (reverse)  (cost=0.0583 rows=20) (actual time=11..11 rows=20 loops=1)\n
달라진 것: Table scan(100만)과 Sort가 사라지고, Index scan ... (reverse) 가 딱 20건만 읽었다. 인덱스가 이미 정렬돼 있어 뒤에서부터 20개 걷어오면 끝.

-> Limit: 20 row(s)  (cost=0.0583 rows=20) (actual time=11.4..11.5 rows=20 loops=1)\n    
*/

-- 통계 갱신
ANALYZE TABLE posts;                                                                                                                                 

-- 다시 한번 용량 확인
SELECT table_name,                                                                                                                                      
    ROUND(DATA_LENGTH / 1024 / 1024, 1)  AS data_mb,    -- 행 데이터(클러스터드 인덱스)                                                                
    ROUND(INDEX_LENGTH / 1024 / 1024, 1) AS index_mb,   -- 세컨더리 인덱스 전체 합                                                                     
    ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 1) AS total_mb,                                                                                  
    TABLE_ROWS AS approx_rows                                                                                                                          
  FROM information_schema.TABLES                                                                                                                       
  WHERE TABLE_SCHEMA = 'board' AND TABLE_NAME = 'posts';
  
-- 복합 인덱스
select id, title 
from posts 
where board_id = 3 
order by created_at desc 
limit 20;

explain analyze
select id, title 
from posts 
where board_id = 3 
order by created_at desc 
limit 20;
/*
-> Index scan on posts using idx_posts_created_at (reverse)  (cost=0.502 rows=60) (actual time=23.7..27.1 rows=97 loops=1)\n
   created_at 인덱스를 역방향(최신→과거)으로 걸었고, 실제 97건을 읽었습니다.

-> Filter: (posts.board_id = 3)  (cost=0.502 rows=20) (actual time=23.9..27.2 rows=20 loops=1)\n        
   97건 중 board_id≠3인 77건을 버리고 20건이 통과.
   
-> Limit: 20 row(s)  (cost=0.502 rows=20) (actual time=24.1..27.4 rows=20 loops=1)\n    
   20개가 차는 순간 스캔 중단. filesort가 없다는 점이 핵심입니다. 100만 건 정렬 대신 97건만 읽고 끝났으니 27ms.
*/

/*
mysql optimizer가 고른 전략

               전략               │                                  방식                                   │  예상 비용   │                         
  ───────────────────────────────┼─────────────────────────────────────────────────────────────────────────┼──────────────┤                         
  │ board_id 인덱스 사용            │ board 3의 20만 건 전부 수집 → filesort → 상위 20                        │ 20만 행 정렬 │                         
  ───────────────────────────────┼─────────────────────────────────────────────────────────────────────────┼──────────────┤                         
  │ created_at 인덱스 사용 (선택됨)   │ 최신순으로 인덱스를 거꾸로 걸으며 board_id=3인 것만 주워 20개 차면 중단 │ 수십~수백 행 │


이 계획은 빨라 보이지만 운에 기대는 계획입니다. "최신 글 근처에 board 3 글이 촘촘히 있다"는 가정이 깨지면 무너집니다:                                
- 글이 거의 없는 게시판(예: 전체 100만 중 10건)을 조회하면 → 20개를 채우려고 created_at 인덱스 전체(100만 건)를 다 걸어도 못 채웁니다. 
같은 쿼리가 수초짜리로 돌변하죠.                                                                                                                                 

- 버린 행이 77/97 = 80%나 됩니다. 읽은 것의 20%만 쓸모 있는 낭비 구조
*/

-- 복합 인덱스를 만든다. 컬럼 순서가 핵심 — 조건 컬럼이 앞, 정렬 컬럼이 뒤.
alter table posts add index idx_posts_board_created (board_id, created_at);

EXPLAIN ANALYZE 
SELECT id, title 
FROM posts 
WHERE board_id = 3 
ORDER BY created_at DESC 
LIMIT 20;
/*
-> Index lookup on posts using idx_posts_board_created (board_id=3) (reverse)  (cost=45549 rows=330956) (actual time=28.6..28.7 rows=20 loops=1)\n

-> Limit: 20 row(s)  (cost=45549 rows=20) (actual time=28.7..28.8 rows=20 loops=1)\n
*/


/*
깊은 페이지네이션 — OFFSET의 배신과 keyset
사용자가 4만 페이지째(페이지당 20건)를 눌렀다고 하자.
*/
EXPLAIN ANALYZE 
SELECT id, title 
FROM posts 
ORDER BY id DESC 
LIMIT 20 OFFSET 800000;
/*
-> Index scan on posts using PRIMARY (reverse)  (cost=67778 rows=800020) (actual time=1.3..348 rows=800020 loops=1)\n'
-> Limit/Offset: 20/800000 row(s)  (cost=67778 rows=20) (actual time=374..374 rows=20 loops=1)\n    

374ms, 읽은 행 800,020. OFFSET은 "80만 건 건너뛰기"가 아니라 "80만 건을 읽고 나서 버리기" 다. 
페이지가 깊을수록 정비례로 느려진다.
*/

EXPLAIN ANALYZE 
SELECT id, title 
FROM posts 
WHERE id < 200020 
ORDER BY id DESC 
LIMIT 20;
/*
-> Limit: 20 row(s)  (cost=1.99 rows=8) (actual time=1.16..1.18 rows=8 loops=1)\n 
-> Index range scan on posts using PRIMARY over (id < 200020) (reverse)  (cost=1.99 rows=8) (actual time=0.266..0.286 rows=8 loops=1)\n'
-> Filter: (posts.id < 200020)  (cost=1.99 rows=8) (actual time=1.03..1.06 rows=8 loops=1)\n        
*/

ALTER TABLE posts DROP INDEX idx_posts_board_created;

DELETE FROM posts WHERE title LIKE '성능실습 %';

-- FK가 옮겨 탈 단독 인덱스를 먼저 만들고 —
ALTER TABLE posts ADD INDEX FK78qo1gxd85rcxqojt2cpcmuj6 (board_id);

-- ALTER TABLE posts DROP INDEX idx_posts_board_created;
-- ALTER TABLE posts DROP INDEX idx_posts_created_at;
