package com.example.board.post;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

  // 단계 16 사후 개선(지연 조인)에 의해 미사용 — 조인(@EntityGraph)을 끌고 OFFSET을
  // 지나가면 버릴 행 전부에 조인이 수행돼 deep page에서 5초대가 된다(LAB §6-1 실측).
  // 아래 findIdsByBoardId + findWithBoardAndAuthorByIdIn 2단계로 대체. 교육용으로 보존.
  @EntityGraph(attributePaths = {"board", "author"})
  Page<Post> findByBoardId(Long boardId, Pageable pageable);

  // 단계 16 사후 개선(지연 조인) 1단계: id만 뽑는다 — SELECT/WHERE/ORDER 컬럼이 전부
  // idx_posts_board_created 안에 있어 covering index로만 처리된다(테이블 접근 0).
  // 정렬은 Pageable의 sort를 그대로 승계(기존 offset API와 동일 계약).
  // Page<Long> 반환이라 COUNT 쿼리는 Spring Data가 자동 파생·실행한다.
  @Query("select p.id from Post p where p.board.id = :boardId")
  Page<Long> findIdsByBoardId(@Param("boardId") Long boardId, Pageable pageable);

  // 지연 조인 2단계: 확정된 페이지의 id들만 조인 로딩(IN). 조인이 페이지 크기(≤100)
  // 로 제한된다. IN 결과의 순서는 보장되지 않으므로 호출자가 id 순서로 복원한다.
  @EntityGraph(attributePaths = {"board", "author"})
  List<Post> findWithBoardAndAuthorByIdIn(List<Long> ids);

  // ── 단계 17: 게시글 검색 (FULLTEXT + ngram) ──────────────────────────────────
  // MATCH AGAINST는 JPQL에 없어 native로 내려간다. native에서는 @EntityGraph를 못
  // 쓰므로 작업 9의 지연 조인 패턴을 재사용한다 — 여기서는 id만 뽑고(1단계), 엔티티
  // 로딩은 위의 findWithBoardAndAuthorByIdIn(2단계)이 맡는다. 정렬·커서 조건은
  // keyset 쿼리(작업 2)와 동일 계약이라 PostCursorResponse를 그대로 재사용할 수 있다.
  // 전제: ft_posts_title_content FULLTEXT 인덱스(수동 DDL — POST-SEARCH.md §4-3).
  @Query(value = "select p.id from posts p "
      + "where p.board_id = :boardId "
      + "and match(p.title, p.content) against(:query in boolean mode) "
      + "order by p.created_at desc, p.id desc limit :limit", nativeQuery = true)
  List<Long> searchIdsByBoardId(
      @Param("boardId") Long boardId, @Param("query") String query, @Param("limit") int limit);

  // 검색 다음 페이지 — keyset 커서 조건을 native로 복제(동일 시각 동률은 id로 확정).
  @Query(value = "select p.id from posts p "
      + "where p.board_id = :boardId "
      + "and match(p.title, p.content) against(:query in boolean mode) "
      + "and (p.created_at < :lastCreatedAt "
      + "or (p.created_at = :lastCreatedAt and p.id < :lastId)) "
      + "order by p.created_at desc, p.id desc limit :limit", nativeQuery = true)
  List<Long> searchIdsByBoardIdAfterCursor(
      @Param("boardId") Long boardId,
      @Param("query") String query,
      @Param("lastCreatedAt") LocalDateTime lastCreatedAt,
      @Param("lastId") Long lastId,
      @Param("limit") int limit);

  // 단계 16: keyset 페이지네이션 첫 페이지 — 커서 없이 최신순 상위 N건.
  // 정렬 기준은 (createdAt desc, id desc) 복합 — createdAt이 같은 행이 있어도
  // id가 순서를 확정하므로 페이지 경계에서 글이 빠지거나 중복되지 않는다.
  @EntityGraph(attributePaths = {"board", "author"})
  List<Post> findByBoardIdOrderByCreatedAtDescIdDesc(Long boardId, Limit limit);

  // 단계 16: keyset 페이지네이션 다음 페이지 — "직전 페이지 마지막 행(커서)보다 오래된 것"만.
  // OFFSET처럼 앞 페이지를 다시 세지 않고 idx_posts_board_created로 커서 위치에
  // 바로 점프하므로, 뒤 페이지로 갈수록 느려지는 문제가 없다(LAB 실측 353ms → 0.07ms).
  // (createdAt = 커서 and id < 커서id) 조건이 동일 시각 행들 사이의 이어받기를 보장한다.
  @EntityGraph(attributePaths = {"board", "author"})
  @Query("select p from Post p where p.board.id = :boardId "
      + "and (p.createdAt < :lastCreatedAt "
      + "or (p.createdAt = :lastCreatedAt and p.id < :lastId)) "
      + "order by p.createdAt desc, p.id desc")
  List<Post> findSliceByBoardIdAfterCursor(
      @Param("boardId") Long boardId,
      @Param("lastCreatedAt") LocalDateTime lastCreatedAt,
      @Param("lastId") Long lastId,
      Limit limit);

  // 단건 조회는 컬렉션(images) 하나만 fetch join 하므로 MultipleBagFetchException이 없다.
  // 이미지가 여러 개면 Post row가 중복되므로 distinct로 제거한다. images가 없을 수 있어 left join.
  @Query("select distinct p from Post p "
      + "join fetch p.board join fetch p.author "
      + "left join fetch p.images "
      + "where p.id = :id")
  Optional<Post> findDetailById(@Param("id") Long id);

  // 메서드 보안(@postSecurity.isAuthor)용 — 작성자 id만 가볍게 조회(엔티티 로딩 없이 소유권 판단)
  @Query("select p.author.id from Post p where p.id = :id")
  Optional<Long> findAuthorIdById(@Param("id") Long id);
}
