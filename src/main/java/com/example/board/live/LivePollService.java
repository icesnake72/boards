package com.example.board.live;

import com.example.board.global.exception.DuplicateException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import com.example.board.live.counter.LiveCounterStore;
import com.example.board.live.dto.LivePayloads;
import com.example.board.live.dto.PollCreateRequest;
import com.example.board.live.dto.PollResponse;
import com.example.board.live.sse.LiveChangedEvent;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LivePollService {

  private final LiveEventService liveEventService;
  private final PollRepository pollRepository;
  private final LiveCounterStore counterStore;
  private final ApplicationEventPublisher publisher;

  @Transactional
  public PollResponse create(String code, PollCreateRequest request) {
    LiveEvent event = liveEventService.getEvent(code);
    event.assertOpen();
    List<String> options = request.options().stream().map(String::strip).toList();
    Poll poll = pollRepository.save(new Poll(event, request.title().strip(), options));
    PollResponse response = PollResponse.of(poll, Map.of(), null);
    log.info("투표 생성: eventId={}, pollId={}, options={}", event.getId(), poll.getId(), options.size());
    publisher.publishEvent(new LiveChangedEvent(event.getId(), LiveChangedEvent.POLL_CREATED, response));
    return response;
  }

  // 검증 순서: 존재(404) → 이벤트 종료(409) → 투표 마감(409) → 이 투표의 선택지인가(404) → 1인 1표(409).
  @Transactional(readOnly = true)
  public PollResponse vote(Long pollId, Long optionId, Long userId) {
    Poll poll = pollRepository.findWithEventAndOptionsById(pollId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.POLL_NOT_FOUND));
    poll.getEvent().assertOpen();
    poll.assertOpen();
    if (!poll.hasOption(optionId)) {
      throw new NotFoundException(ErrorCode.POLL_OPTION_NOT_FOUND);
    }
    if (!counterStore.vote(pollId, optionId, userId)) {
      throw new DuplicateException(ErrorCode.ALREADY_VOTED);
    }
    Map<Long, Long> counts = counterStore.voteCounts(pollId);
    publisher.publishEvent(new LiveChangedEvent(poll.getEvent().getId(), LiveChangedEvent.POLL_VOTED,
        PollResponse.of(poll, counts, null)));
    return PollResponse.of(poll, counts, optionId);
  }

  @Transactional
  public void close(Long pollId) {
    Poll poll = pollRepository.findWithEventAndOptionsById(pollId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.POLL_NOT_FOUND));
    if (!poll.isOpen()) {
      return;
    }
    poll.close();
    publisher.publishEvent(new LiveChangedEvent(poll.getEvent().getId(), LiveChangedEvent.POLL_CLOSED,
        new LivePayloads.IdRef(pollId)));
  }
}
