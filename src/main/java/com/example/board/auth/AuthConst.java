package com.example.board.auth;

// 인증 관련 키를 한 곳에서 관리한다 (문자열 오타 방지).
// 단계 2(JWT)부터는 세션이 아니라 request attribute 키로 쓰인다 — 필터가 심고 Resolver가 읽는다.
public final class AuthConst {

  public static final String LOGIN_USER_ID = "loginUserId";

  private AuthConst() {
  }
}
