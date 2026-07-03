package com.example.board.user;

import com.example.board.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(
    name = "uk_users_provider_provider_id", columnNames = {"provider", "provider_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 50)
  private String username;

  @Column(nullable = false, unique = true, length = 100)
  private String email;

  // BCrypt 해시가 저장된다. 평문 저장 절대 금지.
  @Column(nullable = false)
  private String password;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private Role role;

  // 단계 7: 가입 경로. 자체 가입은 LOCAL, 카카오 로그인은 KAKAO.
  // 기존 테이블에 rows가 있다면 컬럼 추가 후 UPDATE users SET provider = 'LOCAL' 필요(작업 순서 문서 참고).
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private AuthProvider provider;

  // 소셜 제공자가 발급한 회원 고유번호(카카오 회원번호). LOCAL 사용자는 null.
  @Column(name = "provider_id", length = 100)
  private String providerId;

  public User(String username, String email, String password, Role role) {
    this(username, email, password, role, AuthProvider.LOCAL, null);
  }

  public User(
      String username, String email, String password, Role role,
      AuthProvider provider, String providerId) {
    this.username = username;
    this.email = email;
    this.password = password;
    this.role = role;
    this.provider = provider;
    this.providerId = providerId;
  }
}
