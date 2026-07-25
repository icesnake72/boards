package com.example.board.reaction.dto;

import com.example.board.reaction.ReactionType;
import jakarta.validation.constraints.NotNull;

// 반응 API 요청 body. 잘못된 enum 문자열은 메시지 컨버터 단계에서 걸러진다(MALFORMED_REQUEST).
public record ReactionRequest(@NotNull ReactionType type) {
}
