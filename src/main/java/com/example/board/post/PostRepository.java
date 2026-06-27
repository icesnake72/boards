package com.example.board.post;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<Post, Long> {

  // @EntityGraph: 목록 조회 시 board, author를 한 쿼리로 함께 로딩 → N+1 방지
  @EntityGraph(attributePaths = {"board", "author"})
  Page<Post> findByBoardId(Long boardId, Pageable pageable);

  @Query("select p from Post p join fetch p.board join fetch p.author where p.id = :id")
  Optional<Post> findDetailById(@Param("id") Long id);

  // 메서드 보안(@postSecurity.isAuthor)용 — 작성자 id만 가볍게 조회(엔티티 로딩 없이 소유권 판단)
  @Query("select p.author.id from Post p where p.id = :id")
  Optional<Long> findAuthorIdById(@Param("id") Long id);
}
