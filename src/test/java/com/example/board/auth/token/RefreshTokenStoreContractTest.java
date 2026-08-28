package com.example.board.auth.token;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// 단계 15: RefreshTokenStore "계약" 테스트 — 어떤 구현이든 지켜야 할 규칙을 InMemory로 검증.
// (Redis 구현은 같은 인터페이스라 로컬 compose E2E에서 동일 계약을 실증한다)
class RefreshTokenStoreContractTest {

  private final RefreshTokenStore store = new InMemoryRefreshTokenStore();

  @Test
  void should_findUserId_afterSave() {
    store.save(1L, "token-a", 60);
    assertThat(store.findUserId("token-a")).contains(1L);
  }

  @Test
  void should_invalidateOldToken_whenUserSavesAgain() {
    store.save(1L, "token-a", 60);
    store.save(1L, "token-b", 60);          // 재로그인 — 사용자당 1개 불변식
    assertThat(store.findUserId("token-a")).isEmpty();
    assertThat(store.findUserId("token-b")).contains(1L);
  }

  @Test
  void should_returnEmpty_whenTokenUnknownOrDeleted() {
    assertThat(store.findUserId("no-such")).isEmpty();
    store.save(1L, "token-a", 60);
    store.deleteByToken("token-a");
    assertThat(store.findUserId("token-a")).isEmpty();
    store.deleteByToken("token-a");          // 멱등 — 두 번 지워도 예외 없음
  }

  @Test
  void should_isolateUsers() {
    store.save(1L, "token-a", 60);
    store.save(2L, "token-b", 60);
    store.deleteByToken("token-a");
    assertThat(store.findUserId("token-b")).contains(2L);   // 남의 토큰은 무사
  }
}
