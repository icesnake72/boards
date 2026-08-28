package com.example.board.auth.token;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// 단계 15: 테스트용 InMemory 구현 — H2가 MySQL을 대신하듯, 테스트에서 Redis를 대신한다.
// 저장소를 인터페이스 뒤로 숨긴 덕에 이렇게 갈아끼울 수 있다는 것 자체가 이 단계의 학습 포인트.
// TTL은 테스트 관점에서 의미가 없어 기록만 하고 강제하지 않는다(만료 시나리오 = 키 삭제로 표현).
public class InMemoryRefreshTokenStore implements RefreshTokenStore {

  private final Map<String, Long> tokenToUser = new ConcurrentHashMap<>();
  private final Map<Long, String> userToToken = new ConcurrentHashMap<>();

  @Override
  public void save(Long userId, String token, long ttlSeconds) {
    String old = userToToken.get(userId);
    if (old != null) {
      tokenToUser.remove(old);          // 사용자당 1개 — 기존 토큰 교체
    }
    tokenToUser.put(token, userId);
    userToToken.put(userId, token);
  }

  @Override
  public Optional<Long> findUserId(String token) {
    return Optional.ofNullable(tokenToUser.get(token));
  }

  @Override
  public void deleteByToken(String token) {
    Long userId = tokenToUser.remove(token);
    if (userId != null) {
      userToToken.remove(userId);
    }
  }

  // 테스트 격리용 — @Transactional 롤백은 인메모리 Map을 되돌리지 못하므로 직접 비운다
  public void clear() {
    tokenToUser.clear();
    userToToken.clear();
  }
}
