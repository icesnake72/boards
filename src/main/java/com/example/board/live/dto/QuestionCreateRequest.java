package com.example.board.live.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QuestionCreateRequest(@NotBlank @Size(max = 300) String content) {
}
