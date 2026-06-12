package com.example.board.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PostCreateRequest(
    @NotBlank @Size(max = 200) String title,
    @NotBlank String content
) {
}
