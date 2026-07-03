package com.example.board.auth.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// 카카오 token endpoint 응답. 필드가 snake_case라 @JsonProperty로 매핑한다.
// 여기의 access token은 "카카오 API 호출용"이다 — 우리 서비스의 JWT와 전혀 다른 토큰임에 주의.
public record KakaoTokenResponse(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("token_type") String tokenType,
    @JsonProperty("refresh_token") String refreshToken,
    @JsonProperty("expires_in") Long expiresIn,
    @JsonProperty("scope") String scope
) {
}
