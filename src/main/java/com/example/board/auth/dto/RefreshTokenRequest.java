package com.example.board.auth.dto;

import jakarta.validation.constraints.NotBlank;

// 재발급(reissue)과 로그아웃(logout)이 공용으로 쓰는 요청 본문
public record RefreshTokenRequest(
    @NotBlank String refreshToken
) {
}
