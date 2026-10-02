package com.example.board.live.counter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 단계 18: 어떤 구현이든 지켜야 할 집계 규칙 — 여기선 InMemory, RedisLiveCounterStoreContractTest가 실제 Redis로 상속 실행.
class LiveCounterStoreContractTest {

  protected LiveCounterStore store;

  @BeforeEach
  void setUpStore() {
    store = createStore();
  }

  // 하위 클래스가 다른 구현(Redis)을 끼워 같은 계약을 검증한다.
  protected LiveCounterStore createStore() {
    return new InMemoryLiveCounterStore();
  }

  @Test
  void should_startAtZero_afterAddQuestion() {
    store.addQuestion(1L, 10L);
    assertThat(store.likeCounts(1L)).containsEntry(10L, 0L);
  }

  @Test
  void should_toggleOnAndOff() {
    store.addQuestion(1L, 10L);
    assertThat(store.toggleLike(1L, 10L, 100L)).isEqualTo(new LikeResult(true, 1));
    assertThat(store.toggleLike(1L, 10L, 100L)).isEqualTo(new LikeResult(false, 0));
    assertThat(store.toggleLike(1L, 10L, 100L)).isEqualTo(new LikeResult(true, 1));
  }

  @Test
  void should_countDistinctUsers() {
    store.addQuestion(1L, 10L);
    store.toggleLike(1L, 10L, 100L);
    assertThat(store.toggleLike(1L, 10L, 200L).likeCount()).isEqualTo(2);
  }

  @Test
  void should_isolateLikedIds_perUserAndEvent() {
    store.addQuestion(1L, 10L);
    store.addQuestion(2L, 20L);
    store.toggleLike(1L, 10L, 100L);
    store.toggleLike(2L, 20L, 100L);
    assertThat(store.likedQuestionIds(1L, 100L)).containsExactly(10L);
    assertThat(store.likedQuestionIds(1L, 200L)).isEmpty();
  }

  @Test
  void should_dropFromCounts_afterRemoveQuestion() {
    store.addQuestion(1L, 10L);
    store.toggleLike(1L, 10L, 100L);
    store.removeQuestion(1L, 10L);
    assertThat(store.likeCounts(1L)).doesNotContainKey(10L);
  }

  @Test
  void should_acceptFirstVoteOnly() {
    assertThat(store.vote(5L, 51L, 100L)).isTrue();
    assertThat(store.vote(5L, 52L, 100L)).isFalse();
    assertThat(store.voteCounts(5L)).containsExactlyInAnyOrderEntriesOf(Map.of(51L, 1L));
    assertThat(store.votedOptionId(5L, 100L)).isEqualTo(51L);
    assertThat(store.votedOptionId(5L, 200L)).isNull();
  }

  @Test
  void should_isolatePolls() {
    store.vote(5L, 51L, 100L);
    assertThat(store.vote(6L, 61L, 100L)).isTrue();
    assertThat(store.voteCounts(6L)).containsEntry(61L, 1L);
  }
}
