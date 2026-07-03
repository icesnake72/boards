package com.example.board.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

// 단계 7: 외부 API 호출용 RestClient를 빈으로 등록한다.
// Boot은 RestClient가 아니라 RestClient.Builder만 자동 구성한다 —
// 클라이언트마다 base-url/타임아웃이 다를 수 있어 "조립은 앱의 몫"으로 남겨두기 때문.
// 우리는 공용 클라이언트 하나면 충분하므로 여기서 완성해 두고 필요한 곳에 주입받는다.
@Configuration
public class RestClientConfig {

  // Boot이 자동 구성한 Builder로 만들면 앱의 ObjectMapper·메시지 컨버터 설정을 그대로 물려받는다
  @Bean
  public RestClient restClient(RestClient.Builder builder) {
    return builder.build();
  }
}
