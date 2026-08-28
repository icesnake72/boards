package com.example.board.auth.token;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 단계 15: refresh token의 Redis 구현 — 양방향 2키 1쌍 스키마.
//   rt:{token}       → userId   (reissue/logout의 findByToken 경로)
//   rt:user:{userId} → token    (재로그인 시 기존 토큰을 찾아 폐기 — 사용자당 1개 불변식)
// 두 키 모두 TTL 14일 — 만료되면 키가 사라지므로 "없음 = 무효" 하나로 단순해진다.
@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

  private static final String TOKEN_KEY_PREFIX = "rt:";
  private static final String USER_KEY_PREFIX = "rt:user:";

  private final StringRedisTemplate redis;

  @Override
  public void save(Long userId, String token, long ttlSeconds) {
    // 기존 토큰이 있으면 먼저 폐기 — 옛 refresh token으로는 더 이상 재발급이 안 된다
    String oldToken = redis.opsForValue().get(USER_KEY_PREFIX + userId);
    if (oldToken != null) {
      redis.delete(TOKEN_KEY_PREFIX + oldToken);
    }
    Duration ttl = Duration.ofSeconds(ttlSeconds);
    redis.opsForValue().set(TOKEN_KEY_PREFIX + token, String.valueOf(userId), ttl);
    redis.opsForValue().set(USER_KEY_PREFIX + userId, token, ttl);
  }

  @Override
  public Optional<Long> findUserId(String token) {
    String userId = redis.opsForValue().get(TOKEN_KEY_PREFIX + token);
    return Optional.ofNullable(userId).map(Long::valueOf);
  }

  @Override
  public void deleteByToken(String token) {
    String userId = redis.opsForValue().get(TOKEN_KEY_PREFIX + token);
    redis.delete(TOKEN_KEY_PREFIX + token);
    if (userId != null) {
      redis.delete(USER_KEY_PREFIX + userId);
    }
  }
}
