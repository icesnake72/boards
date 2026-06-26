package com.example.board.auth;

import com.example.board.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 강의 포인트: stateless access token과 달리, refresh token은 서버 DB에 저장해 재발급 때 검증한다(stateful).
// 한 사용자당 하나만 둔다(userId unique). 평문 UUID를 저장하지만, 운영에선 해시 저장을 권장한다.
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private Long userId;

  @Column(nullable = false, unique = true)
  private String token;

  @Column(nullable = false)
  private LocalDateTime expiresAt;

  public RefreshToken(Long userId, String token, LocalDateTime expiresAt) {
    this.userId = userId;
    this.token = token;
    this.expiresAt = expiresAt;
  }

  // 로그인 시 기존 토큰을 새 값으로 교체한다
  public void update(String token, LocalDateTime expiresAt) {
    this.token = token;
    this.expiresAt = expiresAt;
  }

  public boolean isExpired() {
    return expiresAt.isBefore(LocalDateTime.now());
  }
}
