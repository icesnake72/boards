import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 개발 서버에서 /api 를 백엔드로 프록시(로컬 npm run dev 용).
// 컨테이너 배포에서는 이 프록시가 아니라 Nginx(nginx.conf)가 프록시를 담당한다.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": "http://localhost:8090",
    },
  },
});
