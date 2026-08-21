#!/usr/bin/env bash
# ────────────────────────────────────────────────────────────────────────────
# 서버(Lightsail) 배포 스크립트 — GitHub Actions(deploy.yml)가 SSH로 실행한다.
#
# 순서: .env 생성 → DB(mysql-8) 준비 → docker compose up --wait → 이미지 정리
#
# 전제:
#   - 이 저장소가 이미 clone/pull 되어 있고, 저장소 루트에서 실행된다
#     (워크플로가 clone·pull 후 ./scripts/deploy.sh 를 호출).
#   - 아래 환경변수가 주입되어 있다(워크플로의 envs: 로 전달):
#     KAKAO_REST_API, KAKAO_SECRET, GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET,
#     DB_NAME, DB_USERNAME, DB_PASSWORD
#
# 손으로 실행해 볼 수도 있다(디버깅용):
#   export KAKAO_REST_API=... DB_PASSWORD=... (필요 변수들)
#   ./scripts/deploy.sh
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
if [ -z "$(docker ps -aq -f name=^mysql-8$)" ]; then
  docker run -d --name mysql-8 \
    --network board-db-net \
    -e MYSQL_ROOT_PASSWORD="${DB_PASSWORD}" \
    -e MYSQL_DATABASE="${DB_NAME}" \
    -v mysql8-data:/var/lib/mysql \
    mysql:8.0.33 --default-time-zone=+09:00
else
  docker start mysql-8 2>/dev/null || true              # 멈춰 있으면 시작
  docker network connect board-db-net mysql-8 2>/dev/null || true
fi

echo "▶ DB 응답 대기(최대 60초) — 앱이 뜨기 전에 DB가 준비되어야 한다"
for i in $(seq 1 30); do
  if docker exec mysql-8 mysqladmin ping -uroot -p"${DB_PASSWORD}" --silent 2>/dev/null; then
    echo "  mysql-8 ready"; break
  fi
  [ "$i" = 30 ] && { echo "  mysql-8 응답 없음 — 중단"; exit 1; }
  sleep 2
done

echo "▶ 빌드·재기동 + 헬스체크 통과까지 대기(--wait)"
# --wait: healthcheck가 있는 모든 서비스(board-app/board-frontend/board-frontend-react)가
#         healthy 가 될 때까지 기다리고, 하나라도 실패하면 비정상 종료(exit≠0)한다.
#         set -e 덕분에 그 즉시 배포가 실패로 처리된다 → 별도 검증 루프가 필요 없다.
docker compose up -d --build --wait

echo "▶ 사용 안 하는 옛 이미지 정리(디스크 확보)"
docker image prune -f

echo "▶ 배포 완료 — 최종 상태"
docker compose ps
