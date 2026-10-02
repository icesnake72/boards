package com.example.board.live;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LiveEventRepository extends JpaRepository<LiveEvent, Long> {

  // 응답에 hostUsername이 실리므로 host를 함께 로딩한다(LAZY 추가 쿼리 방지).
  @EntityGraph(attributePaths = "host")
  Optional<LiveEvent> findByCode(String code);

  boolean existsByCode(String code);

  @EntityGraph(attributePaths = "host")
  List<LiveEvent> findByHostIdOrderByIdDesc(Long hostId);

  // @liveSecurity.isHost용 — 엔티티 없이 진행자 id만.
  @Query("select e.host.id from LiveEvent e where e.code = :code")
  Optional<Long> findHostIdByCode(@Param("code") String code);
}
