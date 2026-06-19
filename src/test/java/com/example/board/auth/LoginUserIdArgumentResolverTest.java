package com.example.board.auth;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.board.global.config.WebConfig;
import com.example.board.global.exception.GlobalExceptionHandler;
import com.example.board.profile.ProfileController;
import com.example.board.profile.ProfileService;
import com.example.board.profile.dto.ProfileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProfileController.class)
@Import({WebConfig.class, LoginUserIdArgumentResolver.class, GlobalExceptionHandler.class})
@WithMockUser
class LoginUserIdArgumentResolverTest {

  @Autowired
  MockMvc mockMvc;

  @MockitoBean
  ProfileService profileService;

  @Test
  void should_return401_whenSessionMissing() throws Exception {
    mockMvc.perform(get("/api/v1/profiles/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
  }

  @Test
  void should_injectLoginUserId_whenSessionHasUserId() throws Exception {
    given(profileService.getMyProfile(42L))
        .willReturn(
            new ProfileResponse(42L, "tester", null, null, null, null, null, null));

    mockMvc.perform(get("/api/v1/profiles/me")
            .sessionAttr(SessionConst.LOGIN_USER_ID, 42L))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(42));
  }
}
