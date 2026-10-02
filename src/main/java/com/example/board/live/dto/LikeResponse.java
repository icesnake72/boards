package com.example.board.live.dto;

public record LikeResponse(Long questionId, boolean liked, long likeCount) {
}
