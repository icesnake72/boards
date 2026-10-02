// 단계 18: 라이브 이벤트 SSE 구독.
// EventSource = 브라우저 내장 SSE 클라이언트. 연결이 끊기면 스스로 재연결한다(기본 약 3초 뒤).
// 단, 요청 헤더를 붙일 수 없어 Bearer 토큰을 못 싣는다 → 서버는 이 스트림만 공개로 열었다.
const EVENT_NAMES = [
  "question.created",
  "question.liked",
  "question.deleted",
  "poll.created",
  "poll.voted",
  "poll.closed",
  "event.closed",
];

// 서버 heartbeat(ping)는 25초 간격 → 그 2배 넘게 아무것도 안 오면 연결이 죽은 것으로 본다.
// EventSource는 "열려 있지만 아무것도 오지 않는" half-open 연결(중간 프록시·모바일 망 전환)을
// 스스로 감지하지 못하므로, 이 watchdog이 직접 닫고 새로 연다.
const SILENCE_LIMIT_MS = 60_000;

export function openLiveStream(code, { onConnected, onEvent, onStatus }) {
  const url = `/api/v1/live/events/${encodeURIComponent(code.trim().toUpperCase())}/stream`;
  let source = null;
  let watchdog = null;
  let stopped = false;

  function stop() {
    stopped = true;
    clearTimeout(watchdog);
    source?.close();
  }

  // 무엇이든 받을 때마다 타이머를 다시 건다 — 끝까지 울리면 연결을 새로 만든다.
  function arm() {
    clearTimeout(watchdog);
    watchdog = setTimeout(() => {
      if (stopped) return;
      onStatus?.("reconnecting");
      source.close();
      connect();
    }, SILENCE_LIMIT_MS);
  }

  function connect() {
    source = new EventSource(url);
    onStatus?.("connecting");
    arm();

    // 서버는 구독(재연결 포함)마다 connected를 먼저 보낸다 → 그때마다 스냅샷을 다시 읽어
    // 끊겨 있던 동안 놓친 변화를 메운다(서버가 지난 이벤트를 재전송하지 않는 설계).
    source.addEventListener("connected", () => {
      arm();
      onStatus?.("live");
      onConnected?.();
    });

    source.addEventListener("ping", arm);

    for (const name of EVENT_NAMES) {
      source.addEventListener(name, (e) => {
        arm();
        let data;
        try {
          data = JSON.parse(e.data);
        } catch {
          console.warn("[live] payload 파싱 실패", name, e.data);
          return;
        }
        onEvent(name, data);
        if (name === "event.closed") {
          stop();                      // 종료된 이벤트는 재연결할 이유가 없다(무한 재연결 방지)
          onStatus?.("closed");
        }
      });
    }

    // onerror는 끊김마다 호출된다. CONNECTING이면 브라우저가 재연결을 시도 중이고,
    // CLOSED(예: 서버가 200이 아닌 응답)면 브라우저는 포기하지만 watchdog이 나중에 다시 연다
    // → 어느 쪽이든 사용자에게는 "재연결 중"이다. "연결 종료"는 event.closed로만 표시한다.
    source.onerror = () => {
      if (!stopped) onStatus?.("reconnecting");
    };
  }

  connect();
  return stop;
}
