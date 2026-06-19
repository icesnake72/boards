package com.example.board.auth;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.board.auth.jwt.JwtAuthenticationFilter;
import com.example.board.auth.jwt.JwtTokenProvider;
import com.example.board.global.config.WebConfig;
import com.example.board.global.exception.GlobalExceptionHandler;
import com.example.board.profile.ProfileController;
import com.example.board.profile.ProfileService;
import com.example.board.profile.dto.ProfileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProfileController.class)
@Import({
    WebConfig.class,
    LoginUserIdArgumentResolver.class,
    GlobalExceptionHandler.class,
    JwtAuthenticationFilter.class,
    JwtTokenProvider.class
})
@TestPropertySource(properties = {
    "jwt.secret=bG9jYWwtZGV2LWp3dC1zZWNyZXQta2V5LWZvci1ib2FyZC1sZWN0dXJlLTI1Ng==",
    "jwt.access-token-validity-seconds=3600"
})
@WithMockUser
class LoginUserIdArgumentResolverTest {

  @Autowired
  MockMvc mockMvc;

  @Autowired
  JwtTokenProvider tokenProvider;

  @MockitoBean
  ProfileService profileService;

  @Test
  void should_return401_whenTokenMissing() throws Exception {
    mockMvc.perform(get("/api/v1/profiles/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  void should_injectLoginUserId_whenValidBearerToken() throws Exception {
    given(profileService.getMyProfile(42L))
        .willReturn(
            new ProfileResponse(42L, "tester", null, null, null, null, null, null));
    String token = tokenProvider.createToken(42L);

    mockMvc.perform(get("/api/v1/profiles/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(42));
  }
}
