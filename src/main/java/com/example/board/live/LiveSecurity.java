package com.example.board.live;

import com.example.board.auth.CustomUserDetails;
import com.example.board.global.exception.ErrorCode;
import com.example.board.global.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 단계 18: @PreAuthorize SpEL에서 호출되는 라이브 인가 빈(단계 6 PostSecurity·단계 11 CommentSecurity 패턴).
// 자원이 없으면 404를 보존하기 위해 여기서 NotFoundException, 권한이 없으면 false → 403.
@Component("liveSecurity")
@RequiredArgsConstructor
public class LiveSecurity {

  private final LiveEventRepository eventRepository;
  private final QuestionRepository questionRepository;
  private final PollRepository pollRepository;

  public boolean isHost(String code, CustomUserDetails user) {
    Long hostId = eventRepository.findHostIdByCode(LiveCodeGenerator.normalize(code))
        .orElseThrow(() -> new NotFoundException(ErrorCode.LIVE_EVENT_NOT_FOUND));
    return hostId.equals(user.getId());
  }

  public boolean canDeleteQuestion(Long questionId, CustomUserDetails user) {
    return questionRepository.findOwnershipById(questionId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.QUESTION_NOT_FOUND))
        .allows(user.getId());
  }

  public boolean isPollHost(Long pollId, CustomUserDetails user) {
    Long hostId = pollRepository.findHostIdById(pollId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.POLL_NOT_FOUND));
    return hostId.equals(user.getId());
  }
}
