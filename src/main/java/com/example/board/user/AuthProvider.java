package com.example.board.user;

// 단계 7: 이 사용자가 어떤 경로로 가입했는지 구분한다.
// LOCAL = username/password 자체 가입, 나머지 = 소셜 OAuth2 로그인.
// 소셜 사용자는 password 로그인이 불가능하고(랜덤 해시 저장), provider + providerId 쌍으로 식별된다.
// 이름 규칙: yaml의 registration ID("kakao", "google")를 대문자로 바꾼 것과 일치해야 한다
// (SuccessHandler와 CustomOAuth2UserService가 valueOf(registrationId.toUpperCase())로 변환).
public enum AuthProvider {
  LOCAL,
  KAKAO,
  GOOGLE   // 단계 8 확장
}
