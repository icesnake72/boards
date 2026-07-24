package com.example.board.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// parentId가 null이면 최상위 댓글, 값이 있으면 그 댓글에 대한 대댓글(1단계).
public record CommentCreateRequest(
    @NotBlank @Size(max = 1000) String content,
    Long parentId
) {
}
