package com.example.board.auth.jwt;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 강의 포인트: 서버는 토큰을 검증만 할 뿐 어떤 로그인 상태도 저장하지 않는다(stateless).
// 사용자 식별 정보(username)는 서명된 토큰 안에 담겨 매 요청마다 클라이언트가 보내온다.
// subject를 username으로 둔 이유: 검증 후 UserDetailsService.loadUserByUsername을 그대로 재사용하기 위함(표준).
@Slf4j
@Component
public class JwtTokenProvider {

  private final SecretKey key;
  private final long accessTokenValiditySeconds;

  public JwtTokenProvider(
      @Value("${jwt.secret}") String base64Secret,
      @Value("${jwt.access-token-validity-seconds}") long accessTokenValiditySeconds) {
    // Base64 시크릿을 바이트로 디코드해 HMAC 서명용 SecretKey 생성 (HS256, 최소 32바이트)
    this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
    this.accessTokenValiditySeconds = accessTokenValiditySeconds;
  }

  // username을 subject에 담고 만료시각을 정해 서명된 토큰 문자열을 발급한다
  public String createToken(String username) {
    Date now = new Date();
    Date expiration = new Date(now.getTime() + accessTokenValiditySeconds * 1000);
    return Jwts.builder()
        .subject(username)                   // sub 클레임 = username
        .issuedAt(now)                       // iat = 발급 시각
        .expiration(expiration)              // exp = 만료 시각
        .signWith(key)                       // 시크릿으로 서명 (위조 방지)
        .compact();                          // 헤더.페이로드.서명 문자열로 직렬화
  }

  // 토큰을 검증하며 파싱해 subject(username)를 꺼낸다 (서명/만료 불량이면 예외)
  public String getUsername(String token) {
    return Jwts.parser()
        .verifyWith(key)            // 서명 검증
        .build()
        .parseSignedClaims(token)   // 검증 실패·만료 시 예외 발생
        .getPayload()
        .getSubject();
  }

  // 서명 위변조·만료·형식 오류만 "인증 실패(false)"로 취급한다.
  // JwtException(만료·서명·형식), IllegalArgumentException(null/빈 토큰)만 잡고,
  // 그 외 예외(키 로딩 실패 등 내부 오류)는 삼키지 않고 전파한다 — 401로 둔갑하지 않도록.
  public boolean validateToken(String token) {
    try {
      Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
      return true;
    } catch (JwtException | IllegalArgumentException e) {
      log.debug("invalid jwt: {}", e.getMessage());
      return false;
    }
  }
}
