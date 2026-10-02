package com.example.board.live.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LiveEventCreateRequest(@NotBlank @Size(max = 100) String title) {
}
