package com.example.board.auth.token;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// 단계 15: 테스트용 denylist — TTL 없이 membership만 흉내낸다(TTL 소멸은 Redis 실구현·E2E가 검증).
public class InMemoryTokenDenylist implements TokenDenylist {

  private final Set<String> denied = ConcurrentHashMap.newKeySet();

  @Override
  public void deny(String jti, long remainingSeconds) {
    denied.add(jti);
  }

  @Override
  public boolean isDenied(String jti) {
    return denied.contains(jti);
  }

  public void clear() {
    denied.clear();
  }
}
