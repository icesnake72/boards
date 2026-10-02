package com.example.board.live.counter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// 단계 18: 테스트용 — synchronized로 Lua의 원자성을 흉내낸다(TTL은 Redis 실구현이 담당).
public class InMemoryLiveCounterStore implements LiveCounterStore {

  private final Map<Long, Map<Long, Long>> scores = new HashMap<>();
  private final Map<String, Set<Long>> userLikes = new HashMap<>();

  @Override
  public synchronized void addQuestion(Long eventId, Long questionId) {
    scores.computeIfAbsent(eventId, id -> new HashMap<>()).putIfAbsent(questionId, 0L);
  }

  @Override
  public synchronized void removeQuestion(Long eventId, Long questionId) {
    Map<Long, Long> eventScores = scores.get(eventId);
    if (eventScores != null) {
      eventScores.remove(questionId);
    }
  }

  @Override
  public synchronized LikeResult toggleLike(Long eventId, Long questionId, Long userId) {
    Set<Long> liked = userLikes.computeIfAbsent(eventId + ":" + userId, key -> new HashSet<>());
    boolean nowLiked = liked.add(questionId);
    if (!nowLiked) {
      liked.remove(questionId);
    }
    long count = scores.computeIfAbsent(eventId, id -> new HashMap<>())
        .merge(questionId, nowLiked ? 1L : -1L, Long::sum);
    return new LikeResult(nowLiked, count);
  }

  @Override
  public synchronized Map<Long, Long> likeCounts(Long eventId) {
    return Map.copyOf(scores.getOrDefault(eventId, Map.of()));
  }

  @Override
  public synchronized Set<Long> likedQuestionIds(Long eventId, Long userId) {
    return Set.copyOf(userLikes.getOrDefault(eventId + ":" + userId, Set.of()));
  }

  public synchronized void clear() {
    scores.clear();
    userLikes.clear();
  }
}
