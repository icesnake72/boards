package com.example.board.live;

import com.example.board.global.exception.BusinessException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import com.example.board.live.counter.LiveCounterStore;
import com.example.board.live.dto.LiveEventCreateRequest;
import com.example.board.live.dto.LiveEventResponse;
import com.example.board.live.dto.LivePayloads;
import com.example.board.live.dto.LiveSnapshotResponse;
import com.example.board.live.dto.QuestionResponse;
import com.example.board.live.sse.LiveChangedEvent;
import com.example.board.live.sse.LiveSseRegistry;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveEventService {

  static final int MAX_CODE_ATTEMPTS = 5;

  private final LiveEventRepository eventRepository;
  private final QuestionRepository questionRepository;
  private final UserRepository userRepository;
  private final LiveCounterStore counterStore;
  private final LiveCodeGenerator codeGenerator;
  private final LiveSseRegistry sseRegistry;
  private final ApplicationEventPublisher publisher;

  @Transactional
  public LiveEventResponse create(Long hostId, LiveEventCreateRequest request) {
    User host = userRepository.findById(hostId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
    LiveEvent event = eventRepository.save(new LiveEvent(host, request.title().strip(), issueCode()));
    log.info("라이브 이벤트 생성: id={}, code={}, hostId={}", event.getId(), event.getCode(), hostId);
    return LiveEventResponse.from(event, hostId);
  }

  @Transactional(readOnly = true)
  public List<LiveEventResponse> getMine(Long hostId) {
    return eventRepository.findByHostIdOrderByIdDesc(hostId).stream()
        .map(event -> LiveEventResponse.from(event, hostId))
        .toList();
  }

  @Transactional(readOnly = true)
  public LiveSnapshotResponse getSnapshot(String code, Long viewerId) {
    LiveEvent event = getEvent(code);
    return new LiveSnapshotResponse(
        LiveEventResponse.from(event, viewerId),
        rankedQuestions(event.getId(), viewerId));
  }

  // 멱등 — 이미 종료됐으면 아무 일도 하지 않는다.
  @Transactional
  public void close(String code) {
    LiveEvent event = getEvent(code);
    if (!event.isOpen()) {
      return;
    }
    event.close();
    log.info("라이브 이벤트 종료: id={}, code={}", event.getId(), event.getCode());
    publisher.publishEvent(new LiveChangedEvent(event.getId(), LiveChangedEvent.EVENT_CLOSED,
        new LivePayloads.EventRef(event.getId())));
  }

  @Transactional(readOnly = true)
  public SseEmitter subscribe(String code) {
    LiveEvent event = getEvent(code);
    return event.isOpen() ? sseRegistry.subscribe(event.getId()) : sseRegistry.closed(event.getId());
  }

  // 질문·투표 서비스도 이 메서드로 이벤트를 찾는다(코드 정규화·404 규칙을 한 곳에).
  @Transactional(readOnly = true)
  public LiveEvent getEvent(String code) {
    return eventRepository.findByCode(LiveCodeGenerator.normalize(code))
        .orElseThrow(() -> new NotFoundException(ErrorCode.LIVE_EVENT_NOT_FOUND));
  }

  private List<QuestionResponse> rankedQuestions(Long eventId, Long viewerId) {
    Map<Long, Long> likeCounts = counterStore.likeCounts(eventId);
    Set<Long> liked = counterStore.likedQuestionIds(eventId, viewerId);
    return questionRepository.findByEventId(eventId).stream()
        .map(question -> QuestionResponse.of(question,
            likeCounts.getOrDefault(question.getId(), 0L),
            liked.contains(question.getId()),
            viewerId))
        .sorted(QuestionResponse.RANKING)
        .toList();
  }

  // unique 제약이 최종 방어선이지만, 충돌을 미리 피해 사용자에게 500을 보이지 않는다.
  private String issueCode() {
    for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
      String code = codeGenerator.generate();
      if (!eventRepository.existsByCode(code)) {
        return code;
      }
    }
    log.error("참여 코드 발급 실패: {}회 연속 충돌", MAX_CODE_ATTEMPTS);
    throw new BusinessException(ErrorCode.INTERNAL_ERROR);
  }
}
