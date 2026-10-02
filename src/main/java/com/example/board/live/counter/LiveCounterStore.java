package com.example.board.live.counter;

import java.util.Map;
import java.util.Set;

// 단계 18: 실시간 집계 저장소 계약 — 운영은 Redis, 테스트는 InMemory(단계 15 RefreshTokenStore 패턴).
public interface LiveCounterStore {

  void addQuestion(Long eventId, Long questionId);

  void removeQuestion(Long eventId, Long questionId);

  // 처음 누르면 +1(liked=true), 다시 누르면 -1(liked=false). 확인과 증감이 원자적이어야 한다.
  LikeResult toggleLike(Long eventId, Long questionId, Long userId);

  Map<Long, Long> likeCounts(Long eventId);

  Set<Long> likedQuestionIds(Long eventId, Long userId);
}
