package com.example.board.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

  // recipient 필터로 남의 알림은 결과에 애초에 안 실린다(조용한 인가).
  // actor를 함께 로딩 — 응답 렌더의 actor.username을 위해 필요(N+1 회피).
  // 정렬은 컨트롤러의 @PageableDefault(sort="createdAt", DESC)가 담당한다(메서드명 OrderBy와 중복 제거).
  @EntityGraph(attributePaths = {"actor"})
  Page<Notification> findByRecipientId(Long recipientId, Pageable pageable);

  long countByRecipientIdAndReadIsFalse(Long recipientId);

  // 벌크 UPDATE — 개별 로드 없이 한 쿼리로 처리. clearAutomatically로 영속성 컨텍스트의
  // 낡은 엔티티를 정리해, 같은 트랜잭션에서 이후 조회 시 stale read=false를 보는 것을 막는다.
  @Modifying(clearAutomatically = true)
  @Query("update Notification n set n.read = true "
      + "where n.recipient.id = :recipientId and n.read = false")
  int markAllAsRead(@Param("recipientId") Long recipientId);
}
