// STOMP 클라이언트 래퍼 — 연결 수명주기와 구독 복구를 한 곳에서 관리한다.
//
// 서버(chat-app) 계약:
//   - 핸드셰이크(/ws)는 공개, 인증은 CONNECT 프레임의 Authorization 헤더로 한다
//     (refresh 쿠키는 Path=/api/v1/auth 라 핸드셰이크에 실리지 않는 설계).
//   - SEND/SUBSCRIBE마다 만료·denylist를 재검사하므로 장수명 연결도 로그아웃이 반영된다.
//   - 거부는 ERROR 프레임(native 헤더 code) 후 연결 종료:
//       TOKEN_EXPIRED  → reissue 후 재연결하면 된다 (여기서 자동 처리)
//       LOGIN_REQUIRED → 세션이 무효(로그아웃/계정 문제) — 재시도하지 않고 끊는다
//   - @stomp/stompjs는 재연결해도 구독을 기억하지 않으므로 등록부(subs)로 복구한다.
import { Client } from "@stomp/stompjs";
import { getAccessToken, refreshAccessToken } from "./api.js";

function brokerURL() {
  const scheme = window.location.protocol === "https:" ? "wss" : "ws";
  return `${scheme}://${window.location.host}/ws`;
}

class ChatSocket {
  constructor() {
    this.client = null;
    this.subs = new Map();       // key → { destination, callback, live(StompSubscription) }
    this.nextKey = 1;
    this.tokenExpired = false;   // 직전 ERROR가 TOKEN_EXPIRED였으면 재연결 전에 reissue
    this.onState = null;         // "connected" | "connecting" | "offline" | "unauthorized"
  }

  // 화면에서 상태 배지를 그릴 수 있게 콜백 하나만 받는다
  setStateListener(fn) {
    this.onState = fn;
  }

  emit(state) {
    if (this.onState) this.onState(state);
  }

  activate() {
    if (this.client?.active) return;
    this.client = new Client({
      brokerURL: brokerURL(),
      reconnectDelay: 3000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      // CONNECT 직전마다 호출 — 만료로 끊겼으면 새 access를 받아 헤더를 갱신한다
      beforeConnect: async () => {
        const c = this.client;
        if (!c) return;
        if (this.tokenExpired || !getAccessToken()) {
          this.tokenExpired = false;
          const ok = await refreshAccessToken();
          if (!ok) {
            this.emit("unauthorized");
            c.deactivate();          // refresh도 죽었으면 재연결 루프를 멈춘다
            return;
          }
        }
        c.connectHeaders = { Authorization: `Bearer ${getAccessToken()}` };
      },
      onConnect: () => {
        this.emit("connected");
        // 재연결 포함 — 등록된 구독을 전부 다시 건다(방 재입장, 로비 복구)
        for (const entry of this.subs.values()) {
          entry.live = this.client.subscribe(entry.destination, entry.callback);
        }
      },
      onStompError: (frame) => {
        const code = frame.headers["code"];
        if (code === "TOKEN_EXPIRED") {
          this.tokenExpired = true;  // 서버가 곧 연결을 끊고, stompjs가 재연결한다
        } else if (code === "LOGIN_REQUIRED") {
          this.emit("unauthorized");
          this.deactivate();
        }
      },
      onWebSocketClose: () => {
        for (const entry of this.subs.values()) entry.live = null;
        if (this.client?.active) this.emit("connecting");
      },
    });
    this.emit("connecting");
    this.client.activate();
  }

  deactivate() {
    this.subs.clear();
    this.emit("offline");
    if (this.client) {
      const c = this.client;
      this.client = null;
      c.deactivate();
    }
  }

  // 구독 등록 — 연결 전이면 등록만 해두고 onConnect에서 붙는다. 해제 함수를 돌려준다.
  subscribe(destination, callback) {
    const key = this.nextKey++;
    const entry = { destination, callback, live: null };
    this.subs.set(key, entry);
    if (this.client?.connected) {
      entry.live = this.client.subscribe(destination, callback);
    }
    return () => {
      this.subs.delete(key);
      if (entry.live) entry.live.unsubscribe();  // UNSUBSCRIBE → 서버가 LEAVE 처리
    };
  }

  // JSON 페이로드 구독 헬퍼
  subscribeJson(destination, handler) {
    return this.subscribe(destination, (message) => {
      try {
        handler(JSON.parse(message.body));
      } catch {
        /* TEXT ERROR 등 JSON이 아닌 프레임은 무시 */
      }
    });
  }

  sendMessage(roomId, content) {
    if (!this.client?.connected) throw new Error("연결이 아직 준비되지 않았습니다.");
    this.client.publish({
      destination: `/app/rooms/${roomId}/messages`,
      body: JSON.stringify({ content }),
    });
  }
}

// 앱 전체가 연결 하나를 공유한다(로비·방이 같은 세션의 구독일 뿐)
export const chatSocket = new ChatSocket();
