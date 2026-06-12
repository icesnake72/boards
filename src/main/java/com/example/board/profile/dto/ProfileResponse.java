package com.example.board.profile.dto;

import com.example.board.profile.UserProfile;
import java.time.LocalDate;

public record ProfileResponse(
    Long userId,
    String username,
    String email,
    String nickname,
    String bio,
    String phoneNumber,
    LocalDate birthDate,
    String profileImageUrl
) {

  public static ProfileResponse from(UserProfile profile) {
    return new ProfileResponse(
        profile.getUser().getId(),
        profile.getUser().getUsername(),
        profile.getUser().getEmail(),
        profile.getNickname(),
        profile.getBio(),
        profile.getPhoneNumber(),
        profile.getBirthDate(),
        profile.getProfileImageUrl());
  }
}
