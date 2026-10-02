package com.example.board.live.dto;

// 단계 18: SSE로 나가는 작은 payload들. 스트림은 공개라 userId·개인 상태를 싣지 않는다.
public final class LivePayloads {

  private LivePayloads() {
  }

  public record EventRef(Long eventId) {
  }

  public record IdRef(Long id) {
  }

  public record QuestionLiked(Long id, long likeCount) {
  }
}
