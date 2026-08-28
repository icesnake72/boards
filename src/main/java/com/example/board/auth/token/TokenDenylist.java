package com.example.board.auth.token;

// 단계 15: access token 즉시 폐기 목록(denylist).
// stateless JWT는 발급 후 만료까지 서버가 막을 수 없다는 한계(단계 2)를 해소한다 —
// 로그아웃 시 jti를 "남은 유효시간"만큼 등록하면, 토큰이 자연 만료되는 순간 키도 함께
// 사라지므로 목록이 무한히 쌓이지 않는다(자기정리). 매 요청 조회는 Redis라서 성립하는 비용.
public interface TokenDenylist {

  void deny(String jti, long remainingSeconds);

  boolean isDenied(String jti);
}
