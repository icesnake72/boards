package com.example.board.profile;

import com.example.board.global.entity.BaseTimeEntity;
import com.example.board.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 인증 정보(User)와 부가 정보(UserProfile)를 분리하면
// 보안 민감 데이터와 자주 바뀌는 데이터의 관심사가 나뉜다
@Entity
@Table(name = "user_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProfile extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // LAZY: 프로필을 조회할 때 항상 User가 필요한 것은 아니므로 즉시 로딩하지 않는다
  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false, unique = true)
  private User user;

  @Column(nullable = false, unique = true, length = 50)
  private String nickname;

  @Column(length = 500)
  private String bio;

  @Column(length = 20)
  private String phoneNumber;

  private LocalDate birthDate;

  @Column(length = 500)
  private String profileImageUrl;

  public UserProfile(User user, String nickname, String bio) {
    this.user = user;
    this.nickname = nickname;
    this.bio = bio;
  }

  public void update(
      String nickname, String bio, String phoneNumber, LocalDate birthDate,
      String profileImageUrl) {
    this.nickname = nickname;
    this.bio = bio;
    this.phoneNumber = phoneNumber;
    this.birthDate = birthDate;
    this.profileImageUrl = profileImageUrl;
  }
}
