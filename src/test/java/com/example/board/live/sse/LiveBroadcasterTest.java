package com.example.board.live.sse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.board.live.dto.LivePayloads;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class LiveBroadcasterTest {

  private final LiveSseRegistry registry = mock(LiveSseRegistry.class);
  private final LiveBroadcaster broadcaster = new LiveBroadcaster(registry);

  @Test
  void should_forwardChange_toRegistry() {
    Object payload = new LivePayloads.QuestionLiked(10L, 2);

    broadcaster.on(new LiveChangedEvent(1L, LiveChangedEvent.QUESTION_LIKED, payload));

    verify(registry).broadcast(1L, LiveChangedEvent.QUESTION_LIKED, payload);
    verify(registry, never()).completeAll(any());
  }

  @Test
  void should_completeAll_afterBroadcastingEventClosed() {
    Object payload = new LivePayloads.EventRef(1L);

    broadcaster.on(new LiveChangedEvent(1L, LiveChangedEvent.EVENT_CLOSED, payload));

    InOrder order = inOrder(registry);
    order.verify(registry).broadcast(1L, LiveChangedEvent.EVENT_CLOSED, payload);
    order.verify(registry).completeAll(1L);
  }
}
