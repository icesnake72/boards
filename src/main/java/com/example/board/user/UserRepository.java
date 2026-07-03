package com.example.board.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

  Optional<User> findByUsername(String username);

  // 단계 7: 소셜 사용자는 (provider, providerId) 쌍이 유일한 식별자다 — 재로그인 시 이 조합으로 찾는다
  Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);

  boolean existsByUsername(String username);

  boolean existsByEmail(String email);
}
