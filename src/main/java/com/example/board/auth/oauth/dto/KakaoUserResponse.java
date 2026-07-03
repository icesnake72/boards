package com.example.board.auth.oauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 카카오 사용자 정보(/v2/user/me) 응답을 "평면" 필드 3개로 받는다.
//
// 실제 카카오 JSON은 이렇게 중첩돼 있다:
//   { "id": 4242, "kakao_account": { "email": "...", "profile": { "nickname": "..." } } }
//
// 중첩 구조를 그대로 클래스로 옮기는 대신, 아래 unpackKakaoAccount()가
// 역직렬화 시점에 필요한 두 값만 꺼내 평면 필드에 담는다.
// email/nickname은 동의 항목 미설정·미동의면 null일 수 있다.
@Getter
@NoArgsConstructor
public class KakaoUserResponse {

  @JsonProperty("id")
  private Long id;          // 카카오 회원번호(불변) → 우리 DB의 providerId

  private String email;     // kakao_account.email
  private String nickname;  // kakao_account.profile.nickname

  // 테스트·직접 생성용 — JSON 역직렬화는 기본 생성자 + unpack 메서드를 쓴다
  public KakaoUserResponse(Long id, String email, String nickname) {
    this.id = id;
    this.email = email;
    this.nickname = nickname;
  }

  // Jackson이 "kakao_account" 항목을 만나면 이 메서드에 Map으로 넘겨준다.
  // 중첩 JSON을 평면 필드로 풀어주는 관용 패턴 — DTO 밖에서는 중첩을 몰라도 된다.
  // 동의 항목이 없으면 각 단계의 값이 null일 수 있어, 한 단계씩 확인하며 내려간다.
  @JsonProperty("kakao_account")
  private void unpackKakaoAccount(Map<String, Object> kakaoAccount) {
    if (kakaoAccount == null) {
      return;  // kakao_account 자체가 없으면 email/nickname 모두 null로 남는다
    }

    this.email = (String) kakaoAccount.get("email");

    Object profileValue = kakaoAccount.get("profile");
    if (profileValue == null) {
      return;  // 프로필 동의 항목이 없으면 nickname은 null로 남는다
    }
    Map<?, ?> profile = (Map<?, ?>) profileValue;  // profile은 {"nickname": "..."} 형태의 Map
    this.nickname = (String) profile.get("nickname");
  }
}
