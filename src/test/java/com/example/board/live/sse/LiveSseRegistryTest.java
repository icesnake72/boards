package com.example.board.live.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.board.live.dto.LivePayloads;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class LiveSseRegistryTest {

  // 보낸 프레임을 문자열로 기록하는 가짜 emitter — 실제 HTTP 연결 없이 전송 내용을 검사한다.
  static class RecordingEmitter extends SseEmitter {
    final List<String> frames = new ArrayList<>();

    @Override
    public void send(SseEventBuilder builder) {
      frames.add(builder.build().stream()
          .map(part -> String.valueOf(part.getData()))
          .collect(Collectors.joining()));
    }
  }

  static class BrokenEmitter extends SseEmitter {
    @Override
    public void send(SseEventBuilder builder) throws IOException {
      throw new IOException("broken pipe");
    }
  }

  private final List<RecordingEmitter> created = new ArrayList<>();

  private final LiveSseRegistry registry = new LiveSseRegistry() {
    @Override
    SseEmitter createEmitter() {
      RecordingEmitter emitter = new RecordingEmitter();
      created.add(emitter);
      return emitter;
    }
  };

  @Test
  void should_sendConnectedFirst_andTrackPerEvent() {
    registry.subscribe(1L);
    registry.subscribe(1L);
    registry.subscribe(2L);

    assertThat(registry.subscriberCount(1L)).isEqualTo(2);
    assertThat(registry.subscriberCount(2L)).isEqualTo(1);
    assertThat(created.get(0).frames.get(0)).contains("event:connected");
  }

  @Test
  void should_broadcastOnlyToSameEvent() {
    registry.subscribe(1L);
    registry.subscribe(2L);

    registry.broadcast(1L, LiveChangedEvent.QUESTION_LIKED, new LivePayloads.QuestionLiked(10L, 3));

    assertThat(created.get(0).frames).hasSize(2);
    assertThat(created.get(0).frames.get(1)).contains("event:question.liked");
    assertThat(created.get(1).frames).hasSize(1);
  }

  @Test
  void should_dropEmitter_whenSendFails() {
    LiveSseRegistry broken = new LiveSseRegistry() {
      @Override
      SseEmitter createEmitter() {
        return new BrokenEmitter();
      }
    };

    broken.subscribe(1L);

    assertThat(broken.subscriberCount(1L)).isZero();
  }

  @Test
  void should_forgetEvent_afterCompleteAll() {
    registry.subscribe(1L);
    registry.completeAll(1L);

    assertThat(registry.subscriberCount(1L)).isZero();
    registry.broadcast(1L, LiveChangedEvent.EVENT_CLOSED, new LivePayloads.EventRef(1L));
  }

  @Test
  void should_sendConnectedThenClosed_forClosedEvent() {
    registry.closed(1L);

    assertThat(created.get(0).frames).hasSize(2);
    assertThat(created.get(0).frames.get(0)).contains("event:connected");
    assertThat(created.get(0).frames.get(1)).contains("event:event.closed");
    assertThat(registry.subscriberCount(1L)).isZero();
  }

  // 주석 프레임(:ping)은 EventSource API에 보이지 않는다 → 클라이언트가 침묵을 감지할 수 있게 이름 있는 이벤트로 보낸다.
  @Test
  void should_sendNamedPingEvent_onHeartbeat() {
    registry.subscribe(1L);
    registry.heartbeat();

    assertThat(created.get(0).frames.get(1)).contains("event:ping");
  }
}
