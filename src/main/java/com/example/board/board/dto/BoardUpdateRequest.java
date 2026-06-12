package com.example.board.board.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BoardUpdateRequest(
    @NotBlank @Size(max = 100) String name,
    @Size(max = 255) String description
) {
}
