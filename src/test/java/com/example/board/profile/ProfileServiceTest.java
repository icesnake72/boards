package com.example.board.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.global.exception.DuplicateException;
import com.example.board.profile.dto.ProfileResponse;
import com.example.board.profile.dto.ProfileUpdateRequest;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ProfileServiceTest {

  @Autowired
  ProfileService profileService;

  @Autowired
  UserRepository userRepository;

  @Autowired
  UserProfileRepository userProfileRepository;

  User me;
  User other;

  @BeforeEach
  void setUp() {
    me = userRepository.save(new User("me1", "me1@example.com", "encoded", Role.USER));
    other = userRepository.save(new User("other1", "other1@example.com", "encoded", Role.USER));
    userProfileRepository.save(new UserProfile(me, "내닉네임", null));
    userProfileRepository.save(new UserProfile(other, "남의닉네임", null));
  }

  @Test
  void should_updateAllFields_whenUpdateMyProfile() {
    ProfileUpdateRequest request = new ProfileUpdateRequest(
        "새닉네임", "자기소개", "010-1234-5678",
        LocalDate.of(1990, 1, 1), "https://example.com/me.png");

    ProfileResponse response = profileService.updateMyProfile(me.getId(), request);

    assertThat(response.userId()).isEqualTo(me.getId());
    assertThat(response.username()).isEqualTo("me1");
    assertThat(response.email()).isEqualTo("me1@example.com");
    assertThat(response.nickname()).isEqualTo("새닉네임");
    assertThat(response.bio()).isEqualTo("자기소개");
    assertThat(response.phoneNumber()).isEqualTo("010-1234-5678");
    assertThat(response.birthDate()).isEqualTo(LocalDate.of(1990, 1, 1));
    assertThat(response.profileImageUrl()).isEqualTo("https://example.com/me.png");
  }

  @Test
  void should_throwDuplicateException_whenNicknameUsedByOther() {
    ProfileUpdateRequest request =
        new ProfileUpdateRequest("남의닉네임", null, null, null, null);

    assertThatThrownBy(() -> profileService.updateMyProfile(me.getId(), request))
        .isInstanceOf(DuplicateException.class);
  }

  @Test
  void should_updateProfile_whenKeepingOwnNickname() {
    ProfileUpdateRequest request =
        new ProfileUpdateRequest("내닉네임", "소개만 변경", null, null, null);

    ProfileResponse response = profileService.updateMyProfile(me.getId(), request);

    assertThat(response.nickname()).isEqualTo("내닉네임");
    assertThat(response.bio()).isEqualTo("소개만 변경");
  }
}
