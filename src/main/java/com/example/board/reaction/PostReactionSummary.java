package com.example.board.reaction;

// 게시글 단건의 반응 요약. myReaction은 비로그인이거나 반응 안 했으면 null.
public record PostReactionSummary(long likeCount, long dislikeCount, ReactionType myReaction) {
}
