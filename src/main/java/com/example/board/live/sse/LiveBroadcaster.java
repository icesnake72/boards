package com.example.board.live.sse;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 단계 18: 서비스가 발행한 LiveChangedEvent를 SSE로 내보낸다(단계 12 알림 리스너와 같은 구조).
// AFTER_COMMIT: DB 저장이 롤백되면 "질문 등록됨"을 퍼뜨리지 않는다.
// fallbackExecution=true: 좋아요처럼 트랜잭션 없이 Redis만 바꾼 경우에도 즉시 실행된다
//   (기본값 false면 트랜잭션 밖에서 발행된 이벤트는 조용히 버려진다).
@Component
@RequiredArgsConstructor
public class LiveBroadcaster {

  private final LiveSseRegistry registry;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void on(LiveChangedEvent event) {
    registry.broadcast(event.eventId(), event.name(), event.payload());
    if (LiveChangedEvent.EVENT_CLOSED.equals(event.name())) {
      registry.completeAll(event.eventId());
    }
  }
}
