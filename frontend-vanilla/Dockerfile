# 순수 정적 프론트라 빌드 단계가 필요 없다 — 경량 nginx 이미지에 파일만 얹는다.
FROM nginx:1.27-alpine

# 기본 서버 설정을 우리 것으로 교체(정적 서빙 + /api 프록시)
COPY nginx.conf /etc/nginx/conf.d/default.conf

# 정적 파일 배치 (nginx 기본 문서 루트)
COPY index.html styles.css app.js /usr/share/nginx/html/

EXPOSE 80

# 컨테이너에서 nginx를 포그라운드로 실행(기본 CMD와 동일하지만 명시)
CMD ["nginx", "-g", "daemon off;"]
