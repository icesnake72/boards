# React는 빌드 단계가 필요하다(JSX/모듈 → 정적 번들). 멀티스테이지로
# Node에서 빌드한 산출물(dist)만 경량 Nginx 이미지에 얹는다(순수 JS 버전은 빌드가 없었다).

# ── 1) build stage ─────────────────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /app

# 의존성 캐시 레이어: 매니페스트만 먼저 복사 → 소스만 바뀐 재빌드에서 npm install 재사용
COPY package.json package-lock.json* ./
RUN npm install

# 소스 복사 후 정적 번들 생성(→ /app/dist)
COPY . .
RUN npm run build

# ── 2) runtime stage ───────────────────────────────────────────
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
