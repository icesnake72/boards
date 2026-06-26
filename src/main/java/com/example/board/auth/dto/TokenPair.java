package com.example.board.auth.dto;

// 강의 포인트: 서비스는 access/refresh 두 토큰을 "생성"만 하고, 전달 매체(본문 vs 쿠키)는 컨트롤러가 결정한다.
// 관심사 분리 — 토큰 생성 책임(Service)과 HTTP 표현 책임(Controller)을 나눈다.
public record TokenPair(
    String accessToken,
    String refreshToken,
    long accessTokenValiditySeconds,
    long refreshTokenValiditySeconds
) {
}
