package com.example.board.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

// 단계 18: SSE 구독자가 연결을 끊으면 Spring이 AsyncRequestNotUsableException을 던진다.
// 최후 방어선(handleException)이 받으면 ERROR 로그 + 닫힌 응답에 JSON 쓰기 실패가 반복된다.
// Spring MVC가 실제로 쓰는 resolver로 "어느 @ExceptionHandler가 받는가"를 검증한다.
class ClientDisconnectHandlingTest {

  @Test
  void should_routeClientDisconnect_toQuietHandler_withoutBody() throws Exception {
    AsyncRequestNotUsableException disconnected =
        new AsyncRequestNotUsableException("Servlet container error notification for disconnected client");
    Method handler = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class)
        .resolveMethod(disconnected);

    assertThat(handler).isNotNull();
    assertThat(handler.getName()).isNotEqualTo("handleException");
    assertThat(handler.invoke(new GlobalExceptionHandler(), disconnected)).isNull();
  }
}
