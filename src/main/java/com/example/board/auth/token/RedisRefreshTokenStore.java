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

  /// ValueOperations<String, String> ops = redis.opsForValue();
  /// ops.set("인사말", "안녕하세요");          // SET
  /// ops.get("인사말");                       // GET
  /// ops.set("인증번호", "1234", Duration.ofMinutes(5));   // SET ... EX 300
  /// ops.setIfAbsent("lock", "1", Duration.ofSeconds(10)); // SET NX EX — 분산락 기본형
  /// ops.increment("방문자수");                // INCR

  /**
   * 왜 두 개를 저장하나 — 각 키의 용도
   * 키	조회 시나리오
   * rt:{token} → userId	재발급 요청 처리: 클라이언트가 보낸 refresh token이 유효한지 + 누구 것인지 한 번에 확인
   * rt:user:{userId} → token
   * */
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
