package com.example.board.global.config;

import com.example.board.profile.UserProfile;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 강의 시연용 ADMIN 계정 시드. 운영 환경에서는 사용하지 않는다 (!prod)
@Slf4j
@Component
@Profile("!prod")
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

  private final UserRepository userRepository;
  private final UserProfileRepository userProfileRepository;
  private final PasswordEncoder passwordEncoder;

  @Value("${app.admin.username:admin}")
  private String adminUsername;

  @Value("${app.admin.password:admin1234}")
  private String adminPassword;

  @Override
  @Transactional
  public void run(String... args) {
    if (userRepository.existsByUsername(adminUsername)) {
      return;
    }
    User admin = new User(
        adminUsername,
        "admin@example.com",
        passwordEncoder.encode(adminPassword),
        Role.ADMIN);
    userRepository.save(admin);
    userProfileRepository.save(new UserProfile(admin, "관리자", "강의용 관리자 계정"));
    log.info("ADMIN 시드 계정 생성: username={}", adminUsername);
  }
}
