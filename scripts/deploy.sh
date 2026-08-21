#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────────────────────
# 서버(Lightsail) 배포 스크립트 — GitHub Actions(deploy.yml)가 SSH로 실행한다.
#
# 순서: .env 생성 → DB(mysql-8) 준비 → docker compose up --wait → 이미지 정리
#
# 전제: 저장소 루트에서 실행되고, 아래 환경변수가 주입되어 있다(워크플로 envs:):
#   KAKAO_REST_API, KAKAO_SECRET, GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET,
#   DB_NAME, DB_USERNAME, DB_PASSWORD
# 디버깅: 서버에서 변수 export 후 ./scripts/deploy.sh 로 직접 실행해 볼 수 있다.
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
# until: ping이 성공할 때까지 2초 간격 반복. 60초 초과 시 timeout이 실패(exit 124) → set -e로 배포 중단
timeout 60 bash -c \
  'until docker exec mysql-8 mysqladmin ping -uroot -p"$DB_PASSWORD" --silent 2>/dev/null; do sleep 2; done'
echo "  mysql-8 ready"

echo "▶ 빌드·재기동 + 헬스체크 통과까지 대기(--wait)"
# --wait: healthcheck가 있는 모든 서비스(백엔드·프론트 2종)가 healthy 될 때까지 기다리고,
#         하나라도 실패하면 비정상 종료 → set -e로 배포 실패 처리(별도 검증 루프 불필요)
docker compose up -d --build --wait

echo "▶ 옛 이미지 정리 + 최종 상태"
docker image prune -f
docker compose ps
