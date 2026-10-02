package com.example.board.live;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.board.auth.jwt.JwtTokenProvider;
import com.example.board.live.counter.InMemoryLiveCounterStore;
import com.example.board.live.dto.LiveEventCreateRequest;
import com.example.board.live.dto.PollCreateRequest;
import com.example.board.live.dto.PollResponse;
import com.example.board.live.dto.QuestionCreateRequest;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

// 단계 18: 공개 스트림 / 로그인 쓰기 / 진행자·작성자 인가가 실제 토큰으로 동작하는지.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LiveApiIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired JwtTokenProvider tokenProvider;
  @Autowired LiveEventService liveEventService;
  @Autowired LiveQuestionService liveQuestionService;
  @Autowired LivePollService livePollService;
  @Autowired InMemoryLiveCounterStore counterStore;

  User host;
  User guest;
  String hostToken;
  String guestToken;
  String otherToken;
  String code;

  @BeforeEach
  void setUp() {
    counterStore.clear();
    host = userRepository.save(new User("host1", "host1@example.com", "encoded", Role.USER));
    guest = userRepository.save(new User("guest1", "guest1@example.com", "encoded", Role.USER));
    userRepository.save(new User("other1", "other1@example.com", "encoded", Role.USER));
    hostToken = "Bearer " + tokenProvider.createToken("host1");
    guestToken = "Bearer " + tokenProvider.createToken("guest1");
    otherToken = "Bearer " + tokenProvider.createToken("other1");
    code = liveEventService.create(host.getId(), new LiveEventCreateRequest("회의")).code();
  }

  @Test
  void should_openStream_withoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/live/events/{code}/stream", code))
        .andExpect(status().isOk())
        .andExpect(request().asyncStarted())
        .andExpect(header().string("X-Accel-Buffering", "no"));
  }

  @Test
  void should_return404_forStreamOfUnknownCode() throws Exception {
    mockMvc.perform(get("/api/v1/live/events/{code}/stream", "ZZZZZZ"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LIVE_EVENT_NOT_FOUND"));
  }

  @Test
  void should_return401_forSnapshotWithoutToken() throws Exception {
    mockMvc.perform(get("/api/v1/live/events/{code}", code))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void should_createQuestion_andToggleLike_withToken() throws Exception {
    String body = mockMvc.perform(post("/api/v1/live/events/{code}/questions", code)
            .header(HttpHeaders.AUTHORIZATION, guestToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\": \"질문\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.mine").value(true))
        .andReturn().getResponse().getContentAsString();
    Long questionId = com.jayway.jsonpath.JsonPath.parse(body).read("$.id", Long.class);

    mockMvc.perform(post("/api/v1/live/questions/{id}/like", questionId)
            .header(HttpHeaders.AUTHORIZATION, hostToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.liked").value(true))
        .andExpect(jsonPath("$.likeCount").value(1));
  }

  @Test
  void should_return400_forBlankQuestion() throws Exception {
    mockMvc.perform(post("/api/v1/live/events/{code}/questions", code)
            .header(HttpHeaders.AUTHORIZATION, guestToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\": \"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @Test
  void should_allowOnlyHost_toCloseEvent() throws Exception {
    mockMvc.perform(post("/api/v1/live/events/{code}/close", code)
            .header(HttpHeaders.AUTHORIZATION, guestToken))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/v1/live/events/{code}/close", code)
            .header(HttpHeaders.AUTHORIZATION, hostToken))
        .andExpect(status().isNoContent());
  }

  @Test
  void should_allowAuthorOrHost_toDeleteQuestion() throws Exception {
    Long questionId = liveQuestionService
        .create(code, guest.getId(), new QuestionCreateRequest("질문")).id();

    mockMvc.perform(delete("/api/v1/live/questions/{id}", questionId)
            .header(HttpHeaders.AUTHORIZATION, otherToken))
        .andExpect(status().isForbidden());
    mockMvc.perform(delete("/api/v1/live/questions/{id}", questionId)
            .header(HttpHeaders.AUTHORIZATION, hostToken))
        .andExpect(status().isNoContent());
  }

  @Test
  void should_return404_forDeletingUnknownQuestion() throws Exception {
    mockMvc.perform(delete("/api/v1/live/questions/{id}", 999_999L)
            .header(HttpHeaders.AUTHORIZATION, hostToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("QUESTION_NOT_FOUND"));
  }
  @Test
  void should_allowOnlyHost_toCreatePoll_andValidateOptions() throws Exception {
    String body = "{\"title\": \"점심\", \"options\": [\"김밥\", \"라면\"]}";
    mockMvc.perform(post("/api/v1/live/events/{code}/polls", code)
            .header(HttpHeaders.AUTHORIZATION, guestToken)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/api/v1/live/events/{code}/polls", code)
            .header(HttpHeaders.AUTHORIZATION, hostToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\": \"점심\", \"options\": [\"김밥\"]}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post("/api/v1/live/events/{code}/polls", code)
            .header(HttpHeaders.AUTHORIZATION, hostToken)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.options.length()").value(2));
  }

  @Test
  void should_return409_onSecondVote() throws Exception {
    PollResponse poll = livePollService.create(code,
        new PollCreateRequest("점심", List.of("김밥", "라면")));
    String body = "{\"optionId\": " + poll.options().get(0).id() + "}";

    mockMvc.perform(post("/api/v1/live/polls/{id}/votes", poll.id())
            .header(HttpHeaders.AUTHORIZATION, guestToken)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.myOptionId").value(poll.options().get(0).id()));
    mockMvc.perform(post("/api/v1/live/polls/{id}/votes", poll.id())
            .header(HttpHeaders.AUTHORIZATION, guestToken)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ALREADY_VOTED"));
  }
}
