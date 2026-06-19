package com.example.board.auth.jwt;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 강의 포인트: 서버는 토큰을 검증만 할 뿐 어떤 로그인 상태도 저장하지 않는다(stateless).
// 사용자 식별 정보(userId)는 서명된 토큰 안에 담겨 매 요청마다 클라이언트가 보내온다.
@Slf4j
@Component
public class JwtTokenProvider {

  private final SecretKey key;
  private final long accessTokenValiditySeconds;

  public JwtTokenProvider(
      @Value("${jwt.secret}") String base64Secret,
      @Value("${jwt.access-token-validity-seconds}") long accessTokenValiditySeconds) {
    this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
    this.accessTokenValiditySeconds = accessTokenValiditySeconds;
  }

  public String createToken(Long userId) {
    Date now = new Date();
    Date expiration = new Date(now.getTime() + accessTokenValiditySeconds * 1000);
    return Jwts.builder()
        .subject(String.valueOf(userId))
        .issuedAt(now)
        .expiration(expiration)
        .signWith(key)
        .compact();
  }

  public Long getUserId(String token) {
    String subject = Jwts.parser()
        .verifyWith(key)
        .build()
        .parseSignedClaims(token)
        .getPayload()
        .getSubject();
    return Long.valueOf(subject);
  }

  // 서명 위변조·만료를 검증한다. 어떤 이유로든 유효하지 않으면 false.
  public boolean validateToken(String token) {
    try {
      Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
      return true;
    } catch (Exception e) {
      log.debug("invalid jwt: {}", e.getMessage());
      return false;
    }
  }
}
