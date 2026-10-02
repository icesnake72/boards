package com.example.board.support;

import com.example.board.live.counter.InMemoryLiveCounterStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

// 단계 18: 모든 @SpringBootTest에서 Redis 집계를 InMemory로 대체(TestTokenStoreConfig와 같은 원리).
// 반환 타입을 구체 클래스로 두어 테스트가 clear()를 쓰도록 주입받을 수 있게 한다.
@Configuration
public class TestLiveCounterStoreConfig {

  @Bean
  @Primary
  public InMemoryLiveCounterStore inMemoryLiveCounterStore() {
    return new InMemoryLiveCounterStore();
  }
}
