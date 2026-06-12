package com.example.board.profile;

import com.example.board.global.exception.DuplicateException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import com.example.board.profile.dto.ProfileResponse;
import com.example.board.profile.dto.ProfileUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileService {

  private final UserProfileRepository userProfileRepository;

  @Transactional(readOnly = true)
  public ProfileResponse getMyProfile(Long userId) {
    return ProfileResponse.from(findByUserId(userId));
  }

  @Transactional
  public ProfileResponse updateMyProfile(Long userId, ProfileUpdateRequest request) {
    // 자기 자신의 기존 nickname은 그대로 두고 수정할 수 있어야 하므로 UserIdNot 조건으로 제외
    if (userProfileRepository.existsByNicknameAndUserIdNot(request.nickname(), userId)) {
      throw new DuplicateException(ErrorCode.NICKNAME_DUPLICATED);
    }
    UserProfile profile = findByUserId(userId);
    profile.update(
        request.nickname(),
        request.bio(),
        request.phoneNumber(),
        request.birthDate(),
        request.profileImageUrl());
    return ProfileResponse.from(profile);
  }

  @Transactional(readOnly = true)
  public ProfileResponse getProfile(Long userId) {
    return ProfileResponse.from(findByUserId(userId));
  }

  private UserProfile findByUserId(Long userId) {
    return userProfileRepository.findByUserId(userId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.PROFILE_NOT_FOUND));
  }
}
