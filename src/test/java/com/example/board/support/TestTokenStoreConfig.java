package com.example.board.support;

import com.example.board.auth.token.InMemoryRefreshTokenStore;
import com.example.board.auth.token.InMemoryTokenDenylist;
import com.example.board.auth.token.RefreshTokenStore;
import com.example.board.auth.token.TokenDenylist;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

// 단계 15: 모든 @SpringBootTest 컨텍스트에서 Redis 구현을 InMemory로 대체한다.
// 테스트 클래스패스의 @Configuration은 BoardApplication 컴포넌트 스캔 범위(com.example.board)에
// 있어 자동으로 적용된다 — @Primary가 Redis 빈 대신 이 빈들을 주입시킨다.
// (test/resources의 H2 application.yaml이 MySQL을 대체하는 것과 같은 원리의 "테스트 대체물")
@Configuration
public class TestTokenStoreConfig {

  @Bean
  @Primary
  public RefreshTokenStore inMemoryRefreshTokenStore() {
    return new InMemoryRefreshTokenStore();
  }

  @Bean
  @Primary
  public TokenDenylist inMemoryTokenDenylist() {
    return new InMemoryTokenDenylist();
  }
}
