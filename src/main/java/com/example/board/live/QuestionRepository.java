package com.example.board.live;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionRepository extends JpaRepository<Question, Long> {

  @EntityGraph(attributePaths = "author")
  List<Question> findByEventId(Long eventId);

  // find와 By 사이의 "WithEvent"는 Spring Data가 무시한다 — 이름은 의도 표현용.
  @EntityGraph(attributePaths = "event")
  Optional<Question> findWithEventById(Long id);

  @Query("select new com.example.board.live.QuestionOwnership(q.author.id, q.event.host.id) "
      + "from Question q where q.id = :id")
  Optional<QuestionOwnership> findOwnershipById(@Param("id") Long id);
}
