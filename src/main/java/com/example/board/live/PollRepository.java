package com.example.board.live;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PollRepository extends JpaRepository<Poll, Long> {

  @EntityGraph(attributePaths = "options")
  List<Poll> findByEventIdOrderByIdDesc(Long eventId);

  @EntityGraph(attributePaths = {"event", "options"})
  Optional<Poll> findWithEventAndOptionsById(Long id);

  @Query("select p.event.host.id from Poll p where p.id = :id")
  Optional<Long> findHostIdById(@Param("id") Long id);
}
