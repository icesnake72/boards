package com.example.board.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.global.exception.BusinessException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.live.counter.InMemoryLiveCounterStore;
import com.example.board.live.dto.LiveEventCreateRequest;
import com.example.board.live.dto.LiveEventResponse;
import com.example.board.live.dto.LiveSnapshotResponse;
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
class LiveEventServiceTest {

  @Autowired
  LiveEventService liveEventService;

  @Autowired
  LiveQuestionService liveQuestionService;

  @Autowired
  UserRepository userRepository;

  @Autowired
  InMemoryLiveCounterStore counterStore;

  @Autowired
  ApplicationEvents events;

  User host;
  User guest;

  @BeforeEach
  void setUp() {
    counterStore.clear();
    host = userRepository.save(new User("host1", "host1@example.com", "encoded", Role.USER));
    guest = userRepository.save(new User("guest1", "guest1@example.com", "encoded", Role.USER));
  }

  @Test
  void should_createOpenEvent_withSixCharCode() {
    LiveEventResponse created = liveEventService.create(host.getId(), new LiveEventCreateRequest("주간 회의"));

    assertThat(created.code()).hasSize(6);
    assertThat(created.status()).isEqualTo(LiveStatus.OPEN);
    assertThat(created.host()).isTrue();
    assertThat(created.hostUsername()).isEqualTo("host1");
  }

  @Test
  void should_listMine_newestFirst() {
    liveEventService.create(host.getId(), new LiveEventCreateRequest("첫 번째"));
    liveEventService.create(host.getId(), new LiveEventCreateRequest("두 번째"));

    assertThat(liveEventService.getMine(host.getId()))
        .extracting(LiveEventResponse::title)
        .containsExactly("두 번째", "첫 번째");
    assertThat(liveEventService.getMine(guest.getId())).isEmpty();
  }

  @Test
  void should_rankQuestions_byLikesThenNewest_inSnapshot() {
    String code = liveEventService.create(host.getId(), new LiveEventCreateRequest("회의")).code();
    Long q1 = liveQuestionService.create(code, guest.getId(), new QuestionCreateRequest("첫 질문")).id();
    Long q2 = liveQuestionService.create(code, guest.getId(), new QuestionCreateRequest("둘째 질문")).id();
    Long q3 = liveQuestionService.create(code, host.getId(), new QuestionCreateRequest("셋째 질문")).id();
    liveQuestionService.toggleLike(q2, host.getId());

    LiveSnapshotResponse snapshot = liveEventService.getSnapshot(code, guest.getId());

    assertThat(snapshot.questions()).extracting(QuestionResponse::id).containsExactly(q2, q3, q1);
    assertThat(snapshot.questions().get(0).likeCount()).isEqualTo(1);
    assertThat(snapshot.questions().get(0).likedByMe()).isFalse();
    assertThat(snapshot.questions().get(0).mine()).isTrue();
    assertThat(snapshot.event().host()).isFalse();
  }

  @Test
  void should_findEvent_whenCodeIsLowercaseWithSpaces() {
    String code = liveEventService.create(host.getId(), new LiveEventCreateRequest("회의")).code();

    LiveSnapshotResponse snapshot =
        liveEventService.getSnapshot(" " + code.toLowerCase() + " ", guest.getId());

    assertThat(snapshot.event().code()).isEqualTo(code);
  }

  @Test
  void should_throw404_whenCodeUnknown() {
    assertThatThrownBy(() -> liveEventService.getSnapshot("ZZZZZZ", guest.getId()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.LIVE_EVENT_NOT_FOUND);
  }

  @Test
  void should_closeEvent_andPublishEventClosed() {
    String code = liveEventService.create(host.getId(), new LiveEventCreateRequest("회의")).code();

    liveEventService.close(code);

    assertThat(liveEventService.getSnapshot(code, host.getId()).event().status())
        .isEqualTo(LiveStatus.CLOSED);
    assertThat(events.stream(LiveChangedEvent.class).map(LiveChangedEvent::name))
        .containsExactly(LiveChangedEvent.EVENT_CLOSED);
  }
}
