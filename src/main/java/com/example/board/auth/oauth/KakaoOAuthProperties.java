package com.example.board.auth.oauth;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

// application.yaml의 app.oauth.kakao.* 를 불변 객체로 바인딩한다.
// @RequiredArgsConstructor가 final 필드 6개짜리 생성자를 만들고, 그것이 유일한 생성자라
// Boot 3가 "생성자 바인딩"을 쓴다. 바인딩은 생성자 파라미터 이름에 의존하므로
// -parameters 컴파일 옵션이 전제다(build.gradle — 단계 6에서 SpEL 때문에 이미 켜져 있다).
// BoardApplication의 @ConfigurationPropertiesScan이 이 클래스를 빈으로 등록한다.
@Getter
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "app.oauth.kakao")
public class KakaoOAuthProperties {

  private final String appkey;        // REST API Key (OAuth의 client_id)
  private final String secret;        // Client Secret (token 요청 시 함께 전송)
  private final String callback;      // Redirect URI — 카카오 콘솔 등록값과 문자열이 완전히 같아야 한다
  private final String authorizeUri;  // 인가 코드 발급 페이지 (브라우저 리다이렉트 대상)
  private final String tokenUri;      // 인가 코드 → 토큰 교환 (서버 간 호출)
  private final String userInfoUri;   // 사용자 정보 조회 (서버 간 호출)

  // 주의: @ConfigurationProperties 바인딩은 해석 못 한 placeholder를 에러 없이
  // "${KAKAO_SECRET}" 문자열 그대로 통과시킨다(@Value가 즉시 죽는 것과 다른 점).
  // 그대로 두면 .env가 없어도 기동은 되고 첫 로그인 요청에서야 500으로 터진다.
  // Lombok 생성자에는 검증 코드를 넣을 수 없으므로, 바인딩 직후 실행되는
  // @PostConstruct에서 검증해 fail-fast를 유지한다.
  @PostConstruct
  void validate() {
    requireResolved("appkey(KAKAO_REST_API)", appkey);
    requireResolved("secret(KAKAO_SECRET)", secret);
    requireResolved("callback", callback);
    requireResolved("authorize-uri", authorizeUri);
    requireResolved("token-uri", tokenUri);
    requireResolved("user-info-uri", userInfoUri);
  }

  private static void requireResolved(String name, String value) {
    if (value == null || value.isBlank() || value.contains("${")) {
      throw new IllegalStateException(
          "app.oauth.kakao." + name + " 값이 없다. .env 파일 또는 환경변수를 확인하라 (.env.example 참고)");
    }
  }
}
