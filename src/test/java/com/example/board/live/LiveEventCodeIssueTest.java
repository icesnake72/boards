package com.example.board.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.board.global.exception.BusinessException;
import com.example.board.global.exception.ErrorCode;
import com.example.board.live.counter.LiveCounterStore;
import com.example.board.live.dto.LiveEventCreateRequest;
import com.example.board.live.sse.LiveSseRegistry;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class LiveEventCodeIssueTest {

  @Mock LiveEventRepository eventRepository;
  @Mock QuestionRepository questionRepository;
  @Mock UserRepository userRepository;
  @Mock LiveCounterStore counterStore;
  @Mock LiveCodeGenerator codeGenerator;
  @Mock LiveSseRegistry sseRegistry;
  @Mock ApplicationEventPublisher publisher;

  @InjectMocks
  LiveEventService liveEventService;

  @BeforeEach
  void setUp() {
    when(userRepository.findById(1L))
        .thenReturn(Optional.of(new User("host1", "host1@example.com", "encoded", Role.USER)));
  }

  @Test
  void should_retryWithNewCode_whenCodeCollides() {
    when(codeGenerator.generate()).thenReturn("AAAAAA", "BBBBBB");
    when(eventRepository.existsByCode("AAAAAA")).thenReturn(true);
    when(eventRepository.existsByCode("BBBBBB")).thenReturn(false);
    when(eventRepository.save(any(LiveEvent.class))).thenAnswer(inv -> inv.getArgument(0));

    assertThat(liveEventService.create(1L, new LiveEventCreateRequest("회의")).code())
        .isEqualTo("BBBBBB");
  }

  @Test
  void should_fail_afterMaxCollisions() {
    when(codeGenerator.generate()).thenReturn("AAAAAA");
    when(eventRepository.existsByCode(anyString())).thenReturn(true);

    assertThatThrownBy(() -> liveEventService.create(1L, new LiveEventCreateRequest("회의")))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_ERROR);
    verify(codeGenerator, times(LiveEventService.MAX_CODE_ATTEMPTS)).generate();
  }
}
