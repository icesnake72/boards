package com.example.board.reaction;

// 댓글 하나의 반응 요약. 반응이 전혀 없는 댓글은 EMPTY로 채운다.
public record CommentReactionSummary(long likeCount, long dislikeCount, ReactionType myReaction) {

  private static final CommentReactionSummary EMPTY = new CommentReactionSummary(0, 0, null);

  public static CommentReactionSummary empty() {
    return EMPTY;
  }
}
