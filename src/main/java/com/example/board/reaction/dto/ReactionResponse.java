package com.example.board.reaction.dto;

import com.example.board.reaction.ReactionType;

// 반응 토글 API 응답 — 갱신 후 최신 카운트와 현재 사용자 반응. myReaction은 취소 후 null.
public record ReactionResponse(long likeCount, long dislikeCount, ReactionType myReaction) {
}
