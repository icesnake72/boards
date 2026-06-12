package com.example.board.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record ProfileUpdateRequest(
    @NotBlank @Size(max = 50) String nickname,
    @Size(max = 500) String bio,
    // @Pattern은 null이면 검증을 통과한다 — 선택 입력 필드에 적합
    @Pattern(regexp = "^01[0-9]-\\d{3,4}-\\d{4}$") String phoneNumber,
    @Past LocalDate birthDate,
    @Size(max = 500) String profileImageUrl
) {
}
