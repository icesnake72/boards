package com.example.board.profile;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

  // ProfileResponse가 username/email을 함께 내려주므로
  // @EntityGraph로 User를 한 번에 조회해 LAZY 프록시 추가 쿼리를 막는다
  @EntityGraph(attributePaths = "user")
  Optional<UserProfile> findByUserId(Long userId);

  boolean existsByNickname(String nickname);

  boolean existsByNicknameAndUserIdNot(String nickname, Long userId);
}
