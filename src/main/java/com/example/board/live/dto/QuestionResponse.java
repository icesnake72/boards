package com.example.board.live.dto;

import com.example.board.live.Question;
import java.time.LocalDateTime;
import java.util.Comparator;

public record QuestionResponse(
    Long id,
    String content,
    String authorUsername,
    long likeCount,
    boolean likedByMe,
    boolean mine,
    LocalDateTime createdAt
) {

  // 좋아요 많은 순, 같으면 최신(id 큰) 순 — 동점 규칙이 결정적이어야 화면이 들썩이지 않는다.
  public static final Comparator<QuestionResponse> RANKING =
      Comparator.comparingLong(QuestionResponse::likeCount).reversed()
          .thenComparing(QuestionResponse::id, Comparator.reverseOrder());

  // viewerId가 null이면 공개 브로드캐스트용(mine=false).
  public static QuestionResponse of(Question question, long likeCount, boolean likedByMe,
                                    Long viewerId) {
    return new QuestionResponse(
        question.getId(),
        question.getContent(),
        question.getAuthor().getUsername(),
        likeCount,
        likedByMe,
        viewerId != null && question.isAuthoredBy(viewerId),
        question.getCreatedAt());
  }
}
