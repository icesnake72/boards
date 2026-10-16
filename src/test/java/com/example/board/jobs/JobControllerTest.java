package com.example.board.jobs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.board.auth.jwt.JwtTokenProvider;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 단계 19: HTTP 계약만 검증 — Redis 작업은 가짜(MockitoBean)로 대체한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JobControllerTest {

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired JwtTokenProvider tokenProvider;

  @MockitoBean JobProducer producer;
  @MockitoBean JobStore store;

  String token;

  @BeforeEach
  void setUp() {
    userRepository.save(new User("jobuser1", "jobuser1@example.com", "encoded", Role.USER));
    token = "Bearer " + tokenProvider.createToken("jobuser1");
  }

  private JobView queued(String id) {
    return new JobView(id, JobStatus.QUEUED, 0, null, null, null, "t", "t");
  }

  @Test
  void should_accept_with202_andQueuedStatus() throws Exception {
    given(producer.submit(any())).willReturn(queued("j1"));

    mockMvc.perform(post("/api/v1/jobs")
            .header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"durationSeconds\": 3, \"failTimes\": 0}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").value("j1"))
        .andExpect(jsonPath("$.status").value("QUEUED"));
  }

  @Test
  void should_reject_durationOver10() throws Exception {
    mockMvc.perform(post("/api/v1/jobs")
            .header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"durationSeconds\": 11, \"failTimes\": 0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @Test
  void should_reject_failTimesOver5() throws Exception {
    mockMvc.perform(post("/api/v1/jobs")
            .header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"durationSeconds\": 1, \"failTimes\": 6}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void should_return404_forUnknownJob() throws Exception {
    given(store.find("nope")).willReturn(Optional.empty());

    mockMvc.perform(get("/api/v1/jobs/nope").header(HttpHeaders.AUTHORIZATION, token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
  }

  @Test
  void should_return401_withoutToken() throws Exception {
    mockMvc.perform(post("/api/v1/jobs")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"durationSeconds\": 1, \"failTimes\": 0}"))
        .andExpect(status().isUnauthorized());
  }
}
