package com.example.board.live.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

// List<@NotBlank String>: 컨테이너 요소 제약 — 리스트 크기뿐 아니라 각 선택지 문자열도 검증한다.
public record PollCreateRequest(
    @NotBlank @Size(max = 100) String title,
    @NotNull @Size(min = 2, max = 5) List<@NotBlank @Size(max = 50) String> options
) {
}
