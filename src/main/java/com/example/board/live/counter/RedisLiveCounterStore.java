package com.example.board.live.counter;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

// 단계 18: 좋아요 집계의 Redis 구현.
//   live:event:{eventId}:questions            ZSET  member=questionId, score=좋아요 수
//   live:event:{eventId}:user:{userId}:likes  SET   이 사용자가 좋아요 누른 questionId
@Component
@RequiredArgsConstructor
public class RedisLiveCounterStore implements LiveCounterStore {

  static final long TTL_SECONDS = Duration.ofDays(7).toSeconds();

  // "SADD 결과 확인 → ZINCRBY"를 명령 두 개로 보내면 그 사이에 다른 요청이 끼어든다.
  // Lua 스크립트는 Redis 안에서 통째로 실행되어 중간에 끼어들 틈이 없다(원자성) + 왕복 1회.
  // KEYS[1]=사용자 좋아요 SET, KEYS[2]=질문 ZSET / ARGV[1]=questionId, ARGV[2]=TTL(초)
  private static final String TOGGLE_LIKE_LUA = """
      local liked
      local score
      if redis.call('SADD', KEYS[1], ARGV[1]) == 1 then
        liked = 1
        score = redis.call('ZINCRBY', KEYS[2], 1, ARGV[1])
      else
        redis.call('SREM', KEYS[1], ARGV[1])
        liked = 0
        score = redis.call('ZINCRBY', KEYS[2], -1, ARGV[1])
      end
      redis.call('EXPIRE', KEYS[1], ARGV[2])
      redis.call('EXPIRE', KEYS[2], ARGV[2])
      return {liked, tonumber(score)}
      """;

  // 결과가 [liked, score] 배열이라 List로 받는다(제네릭 클래스 리터럴이 없어 raw 타입).
  @SuppressWarnings("rawtypes")
  private static final RedisScript<List> TOGGLE_LIKE =
      new DefaultRedisScript<>(TOGGLE_LIKE_LUA, List.class);

  private final StringRedisTemplate redis;

  @Override
  public void addQuestion(Long eventId, Long questionId) {
    String key = questionsKey(eventId);
    redis.opsForZSet().add(key, questionId.toString(), 0);
    redis.expire(key, Duration.ofSeconds(TTL_SECONDS));
  }

  @Override
  public void removeQuestion(Long eventId, Long questionId) {
    redis.opsForZSet().remove(questionsKey(eventId), questionId.toString());
  }

  @Override
  public LikeResult toggleLike(Long eventId, Long questionId, Long userId) {
    List<?> result = redis.execute(TOGGLE_LIKE,
        List.of(userLikesKey(eventId, userId), questionsKey(eventId)),
        questionId.toString(), String.valueOf(TTL_SECONDS));
    if (result == null || result.size() != 2) {
      throw new IllegalStateException("toggle-like script returned " + result);
    }
    return new LikeResult(toLong(result.get(0)) == 1L, toLong(result.get(1)));
  }

  @Override
  public Map<Long, Long> likeCounts(Long eventId) {
    Set<TypedTuple<String>> tuples = redis.opsForZSet().rangeWithScores(questionsKey(eventId), 0, -1);
    Map<Long, Long> counts = new HashMap<>();
    if (tuples != null) {
      for (TypedTuple<String> tuple : tuples) {
        Double score = tuple.getScore();
        counts.put(Long.valueOf(tuple.getValue()), score == null ? 0L : score.longValue());
      }
    }
    return counts;
  }

  @Override
  public Set<Long> likedQuestionIds(Long eventId, Long userId) {
    Set<String> members = redis.opsForSet().members(userLikesKey(eventId, userId));
    if (members == null) {
      return Set.of();
    }
    return members.stream().map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
  }

  private static long toLong(Object value) {
    return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
  }

  private static String questionsKey(Long eventId) {
    return "live:event:" + eventId + ":questions";
  }

  private static String userLikesKey(Long eventId, Long userId) {
    return "live:event:" + eventId + ":user:" + userId + ":likes";
  }
}
