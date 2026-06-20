package com.example.board.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.board.auth.jwt.JwtTokenProvider;
import com.example.board.profile.UserProfile;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 강의 포인트: 선언적 인가가 실제 토큰으로 동작하는지 검증한다.
// (1) 토큰 없음 → 401 (2) USER → 게시판 생성 403 (3) ADMIN → 201 (4) 유효 토큰 → /me 200
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityIntegrationTest {

  @Autowired
  MockMvc mockMvc;

  @Autowired
  UserRepository userRepository;

  @Autowired
  UserProfileRepository userProfileRepository;

  @Autowired
  PasswordEncoder passwordEncoder;

  @Autowired
  JwtTokenProvider tokenProvider;

  String userToken;
  String adminToken;

  @BeforeEach
  void setUp() {
    User user = userRepository.save(
        new User("user1", "user1@example.com", passwordEncoder.encode("password123"), Role.USER));
    userProfileRepository.save(new UserProfile(user, "유저닉", null));
    User admin = userRepository.save(
        new User("admin1", "admin1@example.com", passwordEncoder.encode("password123"), Role.ADMIN));
    userProfileRepository.save(new UserProfile(admin, "관리자닉", null));

    userToken = tokenProvider.createToken("user1");
    adminToken = tokenProvider.createToken("admin1");
  }

  @Test
  void should_return401_whenNoTokenOnProtectedEndpoint() throws Exception {
    mockMvc.perform(get("/api/v1/profiles/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  void should_return403_whenUserCreatesBoard() throws Exception {
    String body = """
        {"name": "새게시판", "description": "설명"}
        """;

    mockMvc.perform(post("/api/v1/boards")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void should_return201_whenAdminCreatesBoard() throws Exception {
    String body = """
        {"name": "새게시판", "description": "설명"}
        """;

    mockMvc.perform(post("/api/v1/boards")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("새게시판"));
  }

  @Test
  void should_return200_whenValidTokenGetsMyProfile() throws Exception {
    mockMvc.perform(get("/api/v1/profiles/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("user1"));
  }
}
