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
}
