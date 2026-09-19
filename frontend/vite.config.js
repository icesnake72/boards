import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 개발 서버에서 /api 를 백엔드로 프록시(로컬 npm run dev 용).
// 컨테이너 배포에서는 이 프록시가 아니라 Nginx(nginx.conf)가 프록시를 담당한다.
// 채팅: /api/v1/chat 과 /ws(WebSocket)는 chat-app(8092)으로 — 구체 경로가 먼저 매칭돼야
// 하므로 /api 보다 위에 둔다.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api/v1/chat": "http://localhost:8092",
      "/ws": { target: "ws://localhost:8092", ws: true },
      "/api": "http://localhost:8090",
    },
  },
});
