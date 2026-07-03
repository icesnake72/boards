package com.example.board.auth.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// 카카오 사용자 정보(/v2/user/me) 응답 중 우리가 쓰는 부분만 매핑한다.
// id: 카카오 회원번호(불변, 우리 DB의 providerId가 된다)
// kakao_account.profile.nickname: 프로필 닉네임 (동의 항목: profile_nickname)
// kakao_account.email: 이메일 — "카카오계정(이메일)" 동의 항목이 없거나 미동의면 null이다
public record KakaoUserResponse(
    Long id,
    @JsonProperty("kakao_account") KakaoAccount kakaoAccount
) {

  public record KakaoAccount(
      String email,
      Profile profile
  ) {

    public record Profile(
        String nickname
    ) {
    }
  }

  // 중첩 구조 어디든 null일 수 있어(동의 항목 미설정) 안전하게 꺼내는 헬퍼를 둔다
  public String nickname() {
    if (kakaoAccount == null || kakaoAccount.profile() == null) {
      return null;
    }
    return kakaoAccount.profile().nickname();
  }

  public String email() {
    return kakaoAccount == null ? null : kakaoAccount.email();
  }
}
