package com.example.board.auth.token;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 단계 15: denylist의 Redis 구현 — deny:{jti} 키를 토큰의 남은 유효초만큼만 보관.
@Component
@RequiredArgsConstructor
public class RedisTokenDenylist implements TokenDenylist {

  private static final String KEY_PREFIX = "deny:";

  private final StringRedisTemplate redis;

  @Override
  public void deny(String jti, long remainingSeconds) {
    redis.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofSeconds(remainingSeconds));
  }

  @Override
  public boolean isDenied(String jti) {
    return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
  }
}
