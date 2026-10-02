package com.example.board.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.global.exception.BusinessException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.live.counter.InMemoryLiveCounterStore;
import com.example.board.live.dto.LikeResponse;
import com.example.board.live.dto.LiveEventCreateRequest;
import com.example.board.live.dto.LiveEventResponse;
import com.example.board.live.dto.QuestionCreateRequest;
import com.example.board.live.dto.QuestionResponse;
import com.example.board.live.sse.LiveChangedEvent;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@RecordApplicationEvents
class LiveQuestionServiceTest {

  @Autowired
  LiveEventService liveEventService;

  @Autowired
  LiveQuestionService liveQuestionService;

  @Autowired
  QuestionRepository questionRepository;

  @Autowired
  UserRepository userRepository;

  @Autowired
  InMemoryLiveCounterStore counterStore;

  @Autowired
  ApplicationEvents events;

  User host;
  User guest;
  LiveEventResponse event;

  @BeforeEach
  void setUp() {
    counterStore.clear();
    host = userRepository.save(new User("host1", "host1@example.com", "encoded", Role.USER));
    guest = userRepository.save(new User("guest1", "guest1@example.com", "encoded", Role.USER));
    event = liveEventService.create(host.getId(), new LiveEventCreateRequest("회의"));
  }

  @Test
  void should_createQuestion_registerRanking_andBroadcastWithoutPersonalFlags() {
    QuestionResponse created =
        liveQuestionService.create(event.code(), guest.getId(), new QuestionCreateRequest("  질문입니다  "));

    assertThat(created.content()).isEqualTo("질문입니다");
    assertThat(created.mine()).isTrue();
    assertThat(counterStore.likeCounts(event.id())).containsEntry(created.id(), 0L);
    LiveChangedEvent published = events.stream(LiveChangedEvent.class).findFirst().orElseThrow();
    assertThat(published.name()).isEqualTo(LiveChangedEvent.QUESTION_CREATED);
    assertThat(((QuestionResponse) published.payload()).mine()).isFalse();
  }

  @Test
  void should_rejectQuestion_whenEventClosed() {
    liveEventService.close(event.code());

    assertThatThrownBy(() ->
        liveQuestionService.create(event.code(), guest.getId(), new QuestionCreateRequest("늦은 질문")))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.LIVE_EVENT_CLOSED);
  }

  @Test
  void should_toggleLike_andBroadcastCount() {
    Long questionId =
        liveQuestionService.create(event.code(), guest.getId(), new QuestionCreateRequest("질문")).id();

    LikeResponse first = liveQuestionService.toggleLike(questionId, host.getId());
    LikeResponse second = liveQuestionService.toggleLike(questionId, host.getId());

    assertThat(first.liked()).isTrue();
    assertThat(first.likeCount()).isEqualTo(1);
    assertThat(second.liked()).isFalse();
    assertThat(second.likeCount()).isZero();
    assertThat(events.stream(LiveChangedEvent.class)
        .filter(e -> e.name().equals(LiveChangedEvent.QUESTION_LIKED))).hasSize(2);
  }

  @Test
  void should_rejectLike_whenEventClosed() {
    Long questionId =
        liveQuestionService.create(event.code(), guest.getId(), new QuestionCreateRequest("질문")).id();
    liveEventService.close(event.code());

    assertThatThrownBy(() -> liveQuestionService.toggleLike(questionId, host.getId()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.LIVE_EVENT_CLOSED);
  }

  @Test
  void should_throw404_whenLikingUnknownQuestion() {
    assertThatThrownBy(() -> liveQuestionService.toggleLike(999_999L, host.getId()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.QUESTION_NOT_FOUND);
  }

  @Test
  void should_deleteQuestion_removeRanking_andBroadcast() {
    Long questionId =
        liveQuestionService.create(event.code(), guest.getId(), new QuestionCreateRequest("질문")).id();

    liveQuestionService.delete(questionId);

    assertThat(questionRepository.findById(questionId)).isEmpty();
    assertThat(counterStore.likeCounts(event.id())).doesNotContainKey(questionId);
    assertThat(events.stream(LiveChangedEvent.class).map(LiveChangedEvent::name))
        .contains(LiveChangedEvent.QUESTION_DELETED);
  }
}
