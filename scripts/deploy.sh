#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────────────────────
# 서버(Lightsail) 배포 스크립트 — GitHub Actions(deploy.yml)가 SSH로 실행한다.
#
# 무스왑 설계: 서버는 빌드하지 않는다. CI(GitHub Actions 러너, RAM 7GB)가
# 이미지를 빌드해 GHCR(ghcr.io)에 올리고, 이 스크립트는 pull + 실행만 한다.
# → 2GB 인스턴스에서 gradle/npm 빌드 부하가 사라져 스왑 없이 안전하게 배포된다.
#   (이전: 서버 빌드 + 스왑 2G 보장 — 무스왑 전환 처리에 의해 제거)
#
# 순서: .env 생성 → DB(mysql-8) 준비 → GHCR 로그인 → pull → up --wait → 정리
#
# 전제: 저장소 루트에서 실행되고, 아래 환경변수가 주입되어 있다(워크플로 envs:):
#   KAKAO_REST_API, KAKAO_SECRET, GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET,
#   DB_NAME, DB_USERNAME, DB_PASSWORD, GHCR_USER, GHCR_TOKEN
#   (GHCR_TOKEN은 워크플로의 내장 GITHUB_TOKEN — 실행 중에만 유효한 단명 토큰)
# ────────────────────────────────────────────────────────────────────────────
set -euo pipefail   # 오류·미정의변수·파이프 실패 시 즉시 중단

echo "▶ .env 생성(Secrets → 서버). 카카오 키는 fail-fast라 비면 기동 실패"
cat > .env <<EOF
KAKAO_REST_API=${KAKAO_REST_API}
KAKAO_SECRET=${KAKAO_SECRET}
GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID}
GOOGLE_CLIENT_SECRET=${GOOGLE_CLIENT_SECRET}
DB_NAME=${DB_NAME}
DB_USERNAME=${DB_USERNAME}
DB_PASSWORD=${DB_PASSWORD}
# HTTPS 진입점(caddy) 주소 — 비밀 아님. 스킴 없는 최종형: Caddy가 이 도메인으로
# Let's Encrypt 발급·90일 자동 갱신·http→https 리다이렉트까지 전자동 처리한다.
# (전제: DNS가 이 서버를 직접 가리켜야 한다 — Cloudflare 프록시는 DNS only. HTTPS-DOMAIN.md)
SITE_ADDRESS=sbs.alldayai.org
EOF

echo "▶ 전용 네트워크·mysql-8 준비(없으면 생성, 있으면 그대로)"
docker network create board-db-net 2>/dev/null || true
# start가 성공하면 이미 있는 컨테이너(떠 있으면 no-op), 실패하면(=없음) run으로 새로 생성
docker start mysql-8 2>/dev/null || docker run -d --name mysql-8 \
  --network board-db-net \
  -e MYSQL_ROOT_PASSWORD="${DB_PASSWORD}" \
  -e MYSQL_DATABASE="${DB_NAME}" \
  -v mysql8-data:/var/lib/mysql \
  mysql:8.0.33 --default-time-zone=+09:00
docker network connect board-db-net mysql-8 2>/dev/null || true

echo "▶ DB 응답 대기(최대 60초) — 앱보다 DB가 먼저 준비되어야 한다"
timeout 60 bash -c \
  'until docker exec mysql-8 mysqladmin ping -uroot -p"$DB_PASSWORD" --silent 2>/dev/null; do sleep 2; done'
echo "  mysql-8 ready"

echo "▶ GHCR 로그인(워크플로 단명 토큰 — 패키지가 비공개여도 pull 가능)"
echo "${GHCR_TOKEN}" | docker login ghcr.io -u "${GHCR_USER}" --password-stdin

echo "▶ 이미지 pull (서버 빌드 없음 — CI가 만든 이미지를 받기만 한다)"
docker compose pull

echo "▶ 재기동 + 헬스체크 통과까지 대기(--wait)"
# --no-build: 서버에서 실수로라도 빌드가 돌지 않게 명시(무스왑 설계의 안전핀)
# --wait: healthcheck 있는 서비스 전부 healthy까지 대기, 실패 시 exit≠0 → 배포 실패
docker compose up -d --no-build --wait

docker logout ghcr.io

echo "▶ 옛 이미지 정리 + 최종 상태"
docker image prune -f
docker compose ps
