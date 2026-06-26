package com.example.board.auth.dto;

// 강의 포인트(단계 5): 응답 본문에는 access token만 담는다.
// refresh token은 본문이 아니라 httpOnly 쿠키로 내려간다(JS가 못 읽어 XSS 탈취 방어).
// access token은 본문 → 클라이언트 메모리 보관, refresh token은 httpOnly 쿠키. 이 비대칭이 단계 5의 핵심이다.
public record TokenResponse(
    String accessToken,
    String tokenType,
    long expiresIn
) {

  public static TokenResponse bearer(String accessToken, long expiresIn) {
    return new TokenResponse(accessToken, "Bearer", expiresIn);
  }
}
