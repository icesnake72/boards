package com.example.board.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.example.board.auth.jwt.JwtTokenProvider;
import com.example.board.board.Board;
import com.example.board.board.BoardRepository;
import com.example.board.post.Post;
import com.example.board.post.PostRepository;
import com.example.board.profile.UserProfile;
import com.example.board.profile.UserProfileRepository;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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

  @Autowired
  BoardRepository boardRepository;

  @Autowired
  PostRepository postRepository;

  String userToken;
  String adminToken;
  String otherToken;
  Long postId;

  @BeforeEach
  void setUp() {
    User user = userRepository.save(
        new User("user1", "user1@example.com", passwordEncoder.encode("password123"), Role.USER));
    userProfileRepository.save(new UserProfile(user, "유저닉", null));
    User admin = userRepository.save(
        new User("admin1", "admin1@example.com", passwordEncoder.encode("password123"), Role.ADMIN));
    userProfileRepository.save(new UserProfile(admin, "관리자닉", null));
    User other = userRepository.save(
        new User("other1", "other1@example.com", passwordEncoder.encode("password123"), Role.USER));
    userProfileRepository.save(new UserProfile(other, "타인닉", null));

    Board board = boardRepository.save(new Board("자유게시판", "설명"));
    Post post = postRepository.save(new Post(board, user, "제목", "내용"));
    postId = post.getId();

    userToken = tokenProvider.createToken("user1");
    adminToken = tokenProvider.createToken("admin1");
    otherToken = tokenProvider.createToken("other1");
  }

  @Test
  void should_return401_whenNoTokenOnProtectedEndpoint() throws Exception {
    mockMvc.perform(get("/api/v1/profiles/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  // 단계 10 보안 보강: 모든 응답에 XSS/MIME sniffing 방어 헤더가 실리는지 검증.
  @Test
  void should_includeSecurityHeaders_onResponse() throws Exception {
    mockMvc.perform(get("/api/v1/boards"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Security-Policy",
            "default-src 'none'; img-src 'self'; frame-ancestors 'none'"));
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

  // 메서드 보안 경유 검증: 비로그인 board 생성은 anyRequest().authenticated()로 401(@PreAuthorize 이전).
  @Test
  void should_return401_whenAnonymousCreatesBoard() throws Exception {
    String body = """
        {"name": "새게시판", "description": "설명"}
        """;

    mockMvc.perform(post("/api/v1/boards")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
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

  // 단계 5 핵심: 로그인 응답은 access token만 본문에 담고, refresh token은 httpOnly 쿠키로 내려준다.
  @Test
  void should_setRefreshCookieAndReturnAccessOnly_whenLogin() throws Exception {
    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody("user1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        // 본문에는 refresh token이 없어야 한다(쿠키로만 전달)
        .andExpect(jsonPath("$.refreshToken").doesNotExist())
        // Set-Cookie: refreshToken=...; HttpOnly
        .andExpect(cookie().exists("refreshToken"))
        .andExpect(cookie().httpOnly("refreshToken", true))
        .andExpect(cookie().value("refreshToken", org.hamcrest.Matchers.not("")));
  }

  // 로그인으로 받은 refresh 쿠키를 reissue 요청에 동봉하면 새 access token을 본문으로 받는다.
  @Test
  void should_reissueAccessToken_whenRefreshCookiePresent() throws Exception {
    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody("user1")))
        .andExpect(status().isOk())
        .andReturn();
    Cookie refreshCookie = login.getResponse().getCookie("refreshToken");
    assertThat(refreshCookie).isNotNull();

    mockMvc.perform(post("/api/v1/auth/reissue").cookie(refreshCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.refreshToken").doesNotExist());
  }

  // 단계 5: 본문이 아니라 쿠키에서 refresh token을 읽으므로, 쿠키가 없으면 401이다.
  @Test
  void should_return401_whenReissueWithoutRefreshCookie() throws Exception {
    mockMvc.perform(post("/api/v1/auth/reissue"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
  }

  // 잘못된 refresh 쿠키면 401(INVALID_REFRESH_TOKEN).
  @Test
  void should_return401_whenReissueWithInvalidRefreshCookie() throws Exception {
    mockMvc.perform(post("/api/v1/auth/reissue")
            .cookie(new Cookie("refreshToken", "no-such-token")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
  }

  // 로그아웃은 204와 함께 refresh 쿠키를 maxAge=0(만료)로 내려 클라이언트 쿠키를 제거한다.
  @Test
  void should_expireRefreshCookie_whenLogout() throws Exception {
    MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody("user1")))
        .andReturn();
    Cookie refreshCookie = login.getResponse().getCookie("refreshToken");

    mockMvc.perform(post("/api/v1/auth/logout").cookie(refreshCookie))
        .andExpect(status().isNoContent())
        .andExpect(cookie().maxAge("refreshToken", 0));
  }

  // 단계 6 소유권 메서드 보안: 작성자 본인은 수정 성공(@postSecurity.isAuthor → true).
  // 단계 10: update가 multipart로 전환돼 본문은 "post"(application/json) 파트로 보낸다.
  @Test
  void should_return200_whenAuthorUpdatesPost() throws Exception {
    mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/posts/{id}", postId)
            .file(postPart("수정 제목", "수정 내용"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("수정 제목"));
  }

  // 타인은 isAuthor → false → AccessDeniedException → 403 ACCESS_DENIED(POST_ACCESS_DENIED에서 통합됨).
  @Test
  void should_return403_whenNonAuthorUpdatesPost() throws Exception {
    mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/posts/{id}", postId)
            .file(postPart("수정 제목", "수정 내용"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  // 없는 글은 isAuthor가 NotFoundException → 404(메서드 보안 단계에서도 404를 보존).
  @Test
  void should_return404_whenUpdatingMissingPost() throws Exception {
    mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/posts/{id}", 999999L)
            .file(postPart("수정 제목", "수정 내용"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
  }

  private MockMultipartFile postPart(String title, String content) {
    String json = """
        {"title": "%s", "content": "%s"}
        """.formatted(title, content);
    return new MockMultipartFile(
        "post", "post", MediaType.APPLICATION_JSON_VALUE, json.getBytes());
  }

  @Test
  void should_return204_whenAuthorDeletesPost() throws Exception {
    mockMvc.perform(delete("/api/v1/posts/{id}", postId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
        .andExpect(status().isNoContent());
  }

  @Test
  void should_return403_whenNonAuthorDeletesPost() throws Exception {
    mockMvc.perform(delete("/api/v1/posts/{id}", postId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  private String loginBody(String username) {
    return """
        {"username": "%s", "password": "password123"}
        """.formatted(username);
  }
}
