package com.example.board.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.board.global.exception.BusinessException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.live.counter.InMemoryLiveCounterStore;
import com.example.board.live.dto.LiveEventCreateRequest;
import com.example.board.live.dto.LiveEventResponse;
import com.example.board.live.dto.PollCreateRequest;
import com.example.board.live.dto.PollOptionResponse;
import com.example.board.live.dto.PollResponse;
import com.example.board.live.sse.LiveChangedEvent;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.List;
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
class LivePollServiceTest {

  @Autowired LiveEventService liveEventService;
  @Autowired LivePollService livePollService;
  @Autowired UserRepository userRepository;
  @Autowired InMemoryLiveCounterStore counterStore;
  @Autowired ApplicationEvents events;

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

  private PollResponse createPoll() {
    return livePollService.create(event.code(),
        new PollCreateRequest("점심 메뉴", List.of("김밥", "라면", "돈가스")));
  }

  @Test
  void should_createOpenPoll_withOptionsInOrder() {
    PollResponse poll = createPoll();

    assertThat(poll.status()).isEqualTo(LiveStatus.OPEN);
    assertThat(poll.options()).extracting(PollOptionResponse::text)
        .containsExactly("김밥", "라면", "돈가스");
    assertThat(poll.totalVotes()).isZero();
    assertThat(events.stream(LiveChangedEvent.class).map(LiveChangedEvent::name))
        .containsExactly(LiveChangedEvent.POLL_CREATED);
  }

  @Test
  void should_rejectPoll_whenEventClosed() {
    liveEventService.close(event.code());

    assertThatThrownBy(this::createPoll)
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.LIVE_EVENT_CLOSED);
  }

  @Test
  void should_countVote_andRejectSecondVote() {
    PollResponse poll = createPoll();
    Long ramen = poll.options().get(1).id();

    PollResponse voted = livePollService.vote(poll.id(), ramen, guest.getId());

    assertThat(voted.myOptionId()).isEqualTo(ramen);
    assertThat(voted.totalVotes()).isEqualTo(1);
    assertThat(voted.options().get(1).count()).isEqualTo(1);
    assertThatThrownBy(() -> livePollService.vote(poll.id(), ramen, guest.getId()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_VOTED);
  }

  @Test
  void should_rejectOptionOfOtherPoll() {
    PollResponse first = createPoll();
    PollResponse second = createPoll();

    assertThatThrownBy(() ->
        livePollService.vote(first.id(), second.options().get(0).id(), guest.getId()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.POLL_OPTION_NOT_FOUND);
    assertThat(counterStore.voteCounts(first.id())).isEmpty();
  }

  @Test
  void should_rejectVote_afterPollClosed() {
    PollResponse poll = createPoll();

    livePollService.close(poll.id());

    assertThatThrownBy(() ->
        livePollService.vote(poll.id(), poll.options().get(0).id(), guest.getId()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.POLL_CLOSED);
    assertThat(events.stream(LiveChangedEvent.class).map(LiveChangedEvent::name))
        .contains(LiveChangedEvent.POLL_CLOSED);
  }

  @Test
  void should_includePolls_withMyChoice_inSnapshot() {
    PollResponse poll = createPoll();
    Long kimbap = poll.options().get(0).id();
    livePollService.vote(poll.id(), kimbap, guest.getId());

    List<PollResponse> guestView = liveEventService.getSnapshot(event.code(), guest.getId()).polls();
    List<PollResponse> hostView = liveEventService.getSnapshot(event.code(), host.getId()).polls();

    assertThat(guestView.get(0).myOptionId()).isEqualTo(kimbap);
    assertThat(guestView.get(0).totalVotes()).isEqualTo(1);
    assertThat(hostView.get(0).myOptionId()).isNull();
  }
}
