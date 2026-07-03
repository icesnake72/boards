package com.example.board.auth.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

// application.yaml의 app.oauth.kakao.* 를 불변 record로 바인딩한다.
// @Value 여섯 줄 대신 타입 세이프 바인딩 — BoardApplication의 @ConfigurationPropertiesScan이 활성화한다.
@ConfigurationProperties(prefix = "app.oauth.kakao")
public record KakaoOAuthProperties(
    String appkey,        // REST API Key (OAuth의 client_id)
    String secret,        // Client Secret (token 요청 시 함께 전송)
    String callback,      // Redirect URI — 카카오 콘솔 등록값과 문자열이 완전히 같아야 한다
    String authorizeUri,  // 인가 코드 발급 페이지 (브라우저 리다이렉트 대상)
    String tokenUri,      // 인가 코드 → 토큰 교환 (서버 간 호출)
    String userInfoUri    // 사용자 정보 조회 (서버 간 호출)
) {
}
