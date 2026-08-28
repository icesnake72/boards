package com.example.board.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

  private static final String SECRET =
      "bG9jYWwtZGV2LWp3dC1zZWNyZXQta2V5LWZvci1ib2FyZC1sZWN0dXJlLTI1Ng==";
  private static final String OTHER_SECRET =
      "YW5vdGhlci1zZWNyZXQta2V5LWZvci1qd3QtdGVzdC1ib2FyZC1sZWN0dXJlLTI1Ng==";

  private final JwtTokenProvider provider = new JwtTokenProvider(SECRET, 3600);

  @Test
  void should_roundTripUsername_whenCreateThenGetUsername() {
    String token = provider.createToken("tester1");

    assertThat(provider.getUsername(token)).isEqualTo("tester1");
    assertThat(provider.validateToken(token)).isTrue();
  }

  @Test
  void should_returnFalse_whenTokenExpired() {
    JwtTokenProvider expiredProvider = new JwtTokenProvider(SECRET, -1);
    String expired = expiredProvider.createToken("tester1");

    assertThat(provider.validateToken(expired)).isFalse();
  }

  @Test
  void should_returnFalse_whenSignatureTampered() {
    JwtTokenProvider otherProvider = new JwtTokenProvider(OTHER_SECRET, 3600);
    String foreignToken = otherProvider.createToken("tester1");

    assertThat(provider.validateToken(foreignToken)).isFalse();
  }

  @Test
  void should_returnFalse_whenTokenMalformed() {
    assertThat(provider.validateToken("not-a-jwt")).isFalse();
  }

  // 단계 15: 토큰마다 고유 jti가 발급된다 — denylist의 키
  @Test
  void should_issueUniqueJti_perToken() {
    String t1 = provider.createToken("tester");
    String t2 = provider.createToken("tester");
    org.assertj.core.api.Assertions.assertThat(provider.getJti(t1)).isNotBlank();
    org.assertj.core.api.Assertions.assertThat(provider.getJti(t1))
        .isNotEqualTo(provider.getJti(t2));
  }

  // 단계 15: 남은 유효초는 denylist TTL로 쓰인다 — 0 < remaining ≤ 설정값
  @Test
  void should_returnRemainingSeconds_withinValidity() {
    String token = provider.createToken("tester");
    long remaining = provider.getRemainingSeconds(token);
    org.assertj.core.api.Assertions.assertThat(remaining).isPositive().isLessThanOrEqualTo(3600);
  }
}
