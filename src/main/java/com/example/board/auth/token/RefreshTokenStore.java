package com.example.board.auth.token;

import java.util.Optional;

// 단계 15: refresh token 저장소 추상화.
// 기존 RefreshTokenRepository(JPA)의 세 연산을 그대로 승계하되, 구현을 인터페이스 뒤로 숨긴다 —
// production은 Redis(TTL), 테스트는 InMemory로 갈아끼운다(단계 2에서 H2가 MySQL을 대신한 것과 같은 구도).
public interface RefreshTokenStore {

  // 사용자당 1개 불변식: 기존 토큰이 있으면 교체(옛 토큰은 즉시 무효)
  void save(Long userId, String token, long ttlSeconds);

  // 토큰으로 소유자 조회. 비어 있으면 "무효" — TTL이 만료 검사를 대신하므로 만료 분기가 따로 없다
  Optional<Long> findUserId(String token);

  // 로그아웃 — 이미 없어도 조용히 통과(멱등)
  void deleteByToken(String token);
}
