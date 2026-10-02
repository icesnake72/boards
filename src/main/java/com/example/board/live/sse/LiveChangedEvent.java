package com.example.board.live.sse;

// 단계 18: 서비스 → 브로드캐스터로 넘기는 "무엇이 바뀌었나" 메시지(Spring ApplicationEvent로 발행).
// name은 SSE의 event 필드가 되고, 브라우저는 addEventListener(name)으로 받는다.
public record LiveChangedEvent(Long eventId, String name, Object payload) {

  public static final String CONNECTED = "connected";
  public static final String PING = "ping";
  public static final String QUESTION_CREATED = "question.created";
  public static final String QUESTION_LIKED = "question.liked";
  public static final String QUESTION_DELETED = "question.deleted";
  public static final String EVENT_CLOSED = "event.closed";
  public static final String POLL_CREATED = "poll.created";
  public static final String POLL_VOTED = "poll.voted";
  public static final String POLL_CLOSED = "poll.closed";
}
