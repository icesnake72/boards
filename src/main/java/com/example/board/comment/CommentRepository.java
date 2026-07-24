package com.example.board.comment;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentRepository extends JpaRepository<Comment, Long> {

  // 최상위 댓글만 페이징. author는 @EntityGraph로 함께 로딩(응답의 authorUsername 때문에 필요),
  // children은 Comment의 @BatchSize가 IN 쿼리로 일괄 로딩한다.
  @EntityGraph(attributePaths = {"author"})
  Page<Comment> findByPostIdAndParentIsNull(Long postId, Pageable pageable);

  // 메서드 보안(@commentSecurity.isAuthor)용 — 작성자 id만 가볍게 조회(엔티티 로딩 없이 소유권 판단)
  @Query("select c.author.id from Comment c where c.id = :id")
  Optional<Long> findAuthorIdById(@Param("id") Long id);
}
