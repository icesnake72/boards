package com.example.board.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

  @Autowired
  MockMvc mockMvc;

  @Test
  void should_return404WithErrorResponse_whenPostNotFound() throws Exception {
    mockMvc.perform(get("/api/v1/posts/999999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void should_return400WithFieldErrors_whenSignupRequestInvalid() throws Exception {
    String invalidBody = """
        {"username": "ab", "email": "not-an-email", "password": "123", "nickname": ""}
        """;

    mockMvc.perform(post("/api/v1/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .content(invalidBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.errors").isArray());
  }

  @Test
  void should_return400MalformedRequest_whenJsonBodyBroken() throws Exception {
    String brokenJson = "{\"username\": ";

    mockMvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(brokenJson))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
  }

  @Test
  void should_return400TypeMismatch_whenPathVariableNotLong() throws Exception {
    mockMvc.perform(get("/api/v1/posts/abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"));
  }

  @Test
  void should_return405MethodNotAllowed_whenUnsupportedHttpMethod() throws Exception {
    mockMvc.perform(delete("/api/v1/auth/login"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
  }
}
