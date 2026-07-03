package com.example.board.auth.oauth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

// 카카오의 중첩 JSON이 평면 필드(id/email/nickname)로 풀리는지 검증한다.
// 서비스 테스트는 KakaoOAuthClient를 mock하므로, 실제 역직렬화 경로는 여기서만 검증된다.
class KakaoUserResponseTest {

  // Boot 기본 ObjectMapper처럼 모르는 필드는 무시한다 (connected_at, properties 등)
  private final ObjectMapper objectMapper = new ObjectMapper()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  @Test
  void should_flattenNestedJson_whenFullResponse() throws Exception {
    String json = """
        {
          "id": 4614955682,
          "connected_at": "2026-07-03T10:00:00Z",
          "properties": {"nickname": "김은범"},
          "kakao_account": {
            "email": "user@example.com",
            "profile": {"nickname": "김은범"}
          }
        }
        """;

    KakaoUserResponse response = objectMapper.readValue(json, KakaoUserResponse.class);

    assertThat(response.getId()).isEqualTo(4614955682L);
    assertThat(response.getEmail()).isEqualTo("user@example.com");
    assertThat(response.getNickname()).isEqualTo("김은범");
  }

  @Test
  void should_leaveFieldsNull_whenConsentItemsMissing() throws Exception {
    // 이메일 동의 항목이 없는 앱의 실제 응답 형태 — email 키 자체가 없다
    String json = """
        {"id": 4614955682, "kakao_account": {"profile": {"nickname": "김은범"}}}
        """;

    KakaoUserResponse response = objectMapper.readValue(json, KakaoUserResponse.class);

    assertThat(response.getId()).isEqualTo(4614955682L);
    assertThat(response.getEmail()).isNull();
    assertThat(response.getNickname()).isEqualTo("김은범");
  }

  @Test
  void should_notFail_whenKakaoAccountMissing() throws Exception {
    String json = """
        {"id": 4614955682}
        """;

    KakaoUserResponse response = objectMapper.readValue(json, KakaoUserResponse.class);

    assertThat(response.getId()).isEqualTo(4614955682L);
    assertThat(response.getEmail()).isNull();
    assertThat(response.getNickname()).isNull();
  }
}
