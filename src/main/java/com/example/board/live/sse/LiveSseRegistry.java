package com.example.board.live.sse;

import com.example.board.live.dto.LivePayloads;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// 단계 18: 이벤트별 SSE 구독자 명부. SseEmitter는 "열어 둔 HTTP 응답"을 감싼 객체라 JVM 메모리에 산다
// → 인스턴스가 하나일 때만 이 Map으로 충분하다(여러 대면 Redis Pub/Sub로 전파해야 한다).
@Slf4j
@Component
public class LiveSseRegistry {

  static final long TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

  // 요청 스레드(구독·전파)와 스케줄러 스레드(heartbeat)가 동시에 만지므로 동시성 컬렉션을 쓴다.
  private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

  public SseEmitter subscribe(Long eventId) {
    SseEmitter emitter = createEmitter();
    // compute는 키 단위로 원자적 — 동시에 마지막 구독자가 빠지며 Set이 지워지는 경합을 막는다.
    emitters.compute(eventId, (id, current) -> {
      Set<SseEmitter> target = current != null ? current : ConcurrentHashMap.newKeySet();
      target.add(emitter);
      return target;
    });
    emitter.onCompletion(() -> remove(eventId, emitter));
    emitter.onTimeout(() -> {
      remove(eventId, emitter);
      emitter.complete();
    });
    emitter.onError(e -> remove(eventId, emitter));
    // 첫 이벤트를 바로 보내야 응답 헤더가 flush되어 브라우저가 "연결됨"을 안다.
    send(eventId, emitter, () -> SseEmitter.event()
        .name(LiveChangedEvent.CONNECTED)
        .data(new LivePayloads.EventRef(eventId), MediaType.APPLICATION_JSON));
    log.debug("SSE 구독: eventId={}, subscribers={}", eventId, subscriberCount(eventId));
    return emitter;
  }

  // 종료된 이벤트 — 명부에 넣지 않고 connected + event.closed만 보낸 뒤 바로 닫는다.
  // 브라우저는 event.closed를 받고 스스로 EventSource를 닫아 재연결 루프를 끊는다.
  public SseEmitter closed(Long eventId) {
    SseEmitter emitter = createEmitter();
    try {
      emitter.send(SseEmitter.event()
          .name(LiveChangedEvent.CONNECTED)
          .data(new LivePayloads.EventRef(eventId), MediaType.APPLICATION_JSON));
      emitter.send(SseEmitter.event()
          .name(LiveChangedEvent.EVENT_CLOSED)
          .data(new LivePayloads.EventRef(eventId), MediaType.APPLICATION_JSON));
      emitter.complete();
    } catch (IOException | IllegalStateException e) {
      emitter.completeWithError(e);
    }
    return emitter;
  }

  public void broadcast(Long eventId, String name, Object payload) {
    Set<SseEmitter> targets = emitters.get(eventId);
    if (targets == null) {
      return;
    }
    // SseEventBuilder는 build() 때 내부 버퍼를 바꾸므로 emitter마다 새로 만든다(Supplier).
    for (SseEmitter emitter : targets) {
      send(eventId, emitter, () -> SseEmitter.event()
          .name(name)
          .data(payload, MediaType.APPLICATION_JSON));
    }
  }

  public void completeAll(Long eventId) {
    Set<SseEmitter> targets = emitters.remove(eventId);
    if (targets != null) {
      targets.forEach(SseEmitter::complete);
    }
  }

  // 25초마다 ping 이벤트 — 세 가지 역할:
  // (1) 서버: 끊긴 연결이 전송 실패로 드러나 명부에서 정리된다.
  // (2) 프록시(nginx 등): 바이트가 흐르므로 유휴 연결로 보고 끊지 않는다.
  // (3) 브라우저: "연결은 열려 있는데 아무것도 안 오는"(half-open) 상태를 감지하는 기준이 된다.
  //     주석 프레임(:ping)은 EventSource API에 보이지 않으므로 이름 있는 이벤트로 보낸다.
  @Scheduled(fixedRate = 25_000)
  public void heartbeat() {
    emitters.forEach((eventId, targets) -> targets.forEach(emitter ->
        send(eventId, emitter, () -> SseEmitter.event()
            .name(LiveChangedEvent.PING)
            .data(new LivePayloads.EventRef(eventId), MediaType.APPLICATION_JSON))));
  }

  public int subscriberCount(Long eventId) {
    Set<SseEmitter> targets = emitters.get(eventId);
    return targets == null ? 0 : targets.size();
  }

  // 테스트가 가짜 emitter를 끼울 수 있게 분리(package-private).
  SseEmitter createEmitter() {
    return new SseEmitter(TIMEOUT_MILLIS);
  }

  private void send(Long eventId, SseEmitter emitter, Supplier<SseEmitter.SseEventBuilder> event) {
    try {
      emitter.send(event.get());
    } catch (IOException | IllegalStateException e) {
      // 브라우저 탭을 닫으면 흔히 일어나는 정상 상황 — 경고가 아니라 debug.
      log.debug("SSE 전송 실패, 구독 제거: eventId={}, cause={}", eventId, e.getMessage());
      remove(eventId, emitter);
    }
  }

  private void remove(Long eventId, SseEmitter emitter) {
    emitters.computeIfPresent(eventId, (id, targets) -> {
      targets.remove(emitter);
      return targets.isEmpty() ? null : targets;
    });
  }
}
