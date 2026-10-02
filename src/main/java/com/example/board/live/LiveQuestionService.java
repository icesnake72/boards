package com.example.board.live;

import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import com.example.board.live.counter.LikeResult;
import com.example.board.live.counter.LiveCounterStore;
import com.example.board.live.dto.LikeResponse;
import com.example.board.live.dto.LivePayloads;
import com.example.board.live.dto.QuestionCreateRequest;
import com.example.board.live.dto.QuestionResponse;
import com.example.board.live.sse.LiveChangedEvent;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LiveQuestionService {

  private final LiveEventService liveEventService;
  private final QuestionRepository questionRepository;
  private final UserRepository userRepository;
  private final LiveCounterStore counterStore;
  private final ApplicationEventPublisher publisher;

  @Transactional
  public QuestionResponse create(String code, Long authorId, QuestionCreateRequest request) {
    LiveEvent event = liveEventService.getEvent(code);
    event.assertOpen();
    User author = userRepository.findById(authorId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
    Question question = questionRepository.save(
        new Question(event, author, request.content().strip()));
    counterStore.addQuestion(event.getId(), question.getId());
    publisher.publishEvent(new LiveChangedEvent(event.getId(), LiveChangedEvent.QUESTION_CREATED,
        QuestionResponse.of(question, 0, false, null)));
    return QuestionResponse.of(question, 0, false, authorId);
  }

  // DB는 읽기만 한다 — 좋아요의 원천은 Redis.
  @Transactional(readOnly = true)
  public LikeResponse toggleLike(Long questionId, Long userId) {
    Question question = findWithEvent(questionId);
    LiveEvent event = question.getEvent();
    event.assertOpen();
    LikeResult result = counterStore.toggleLike(event.getId(), questionId, userId);
    publisher.publishEvent(new LiveChangedEvent(event.getId(), LiveChangedEvent.QUESTION_LIKED,
        new LivePayloads.QuestionLiked(questionId, result.likeCount())));
    return new LikeResponse(questionId, result.liked(), result.likeCount());
  }

  // 인가(진행자 또는 작성자)는 컨트롤러의 @liveSecurity가 먼저 확인한다.
  @Transactional
  public void delete(Long questionId) {
    Question question = findWithEvent(questionId);
    Long eventId = question.getEvent().getId();
    questionRepository.delete(question);
    counterStore.removeQuestion(eventId, questionId);
    publisher.publishEvent(new LiveChangedEvent(eventId, LiveChangedEvent.QUESTION_DELETED,
        new LivePayloads.IdRef(questionId)));
  }

  private Question findWithEvent(Long questionId) {
    return questionRepository.findWithEventById(questionId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.QUESTION_NOT_FOUND));
  }
}
