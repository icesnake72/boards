#!/usr/bin/env bash
# verify.sh — 빌드 + 테스트 + 실제 기동 헬스체크 검증 스크립트
#
# exit 0: 통과 (또는 환경 미비로 기동 검증 SKIP — 빌드+테스트 통과가 최소 보증선)
# exit 1: 실패 (원인 로그를 stdout에 출력하므로 그대로 읽고 수정하면 된다)
#
# 사용법: ./scripts/verify.sh
set -u

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERIFY_PORT="${VERIFY_PORT:-8091}"
HEALTH_URL="http://localhost:${VERIFY_PORT}/api/v1/boards"
BOOT_WAIT_MAX_SECONDS=45
BOOT_POLL_INTERVAL=2

APP_PID=""
APP_LOG="$(mktemp -t board-verify-app)"
BUILD_LOG="$(mktemp -t board-verify-build)"

cleanup() {
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null
    for _ in $(seq 1 10); do
      kill -0 "$APP_PID" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$APP_PID" 2>/dev/null
  fi
  rm -f "$APP_LOG" "$BUILD_LOG"
}
trap cleanup EXIT

fail() {
  echo ""
  echo "=== VERIFY FAILED: $1 ==="
  exit 1
}

cd "$PROJECT_ROOT"

# ---------- stage 1: 빌드 + 전체 테스트 (H2 기반, 외부 의존성 없음) ----------
echo "[1/3] build + test: ./gradlew build"
if ! ./gradlew build --console=plain > "$BUILD_LOG" 2>&1; then
  echo ""
  echo "--- gradle output (tail 80) ---"
  tail -80 "$BUILD_LOG"
  echo ""
  echo "--- failed tests ---"
  grep -rl '<failure\|<error' build/test-results/test/*.xml 2>/dev/null \
    | sed 's#.*/TEST-##; s#\.xml$##' || echo "(테스트 결과 XML 없음 — 컴파일 실패 가능성)"
  echo "상세 리포트: build/reports/tests/test/index.html"
  fail "build or tests"
fi
echo "  OK"

# ---------- stage 2: 기동 검증 전제조건 체크 ----------
echo "[2/3] boot check preconditions"

skip_boot() {
  echo "  SKIP boot check: $1"
  echo ""
  echo "=== VERIFY PASSED (build + test only) ==="
  exit 0
}

if ! (echo > /dev/tcp/127.0.0.1/3306) 2>/dev/null; then
  skip_boot "MySQL(127.0.0.1:3306) 미응답"
fi
if [[ ! -f .env ]]; then
  skip_boot ".env 없음 (KAKAO_SECRET 필요 — KakaoOAuthProperties가 기동 시 fail-fast)"
fi
if ! grep -Eq '^KAKAO_SECRET=.+' .env || ! grep -Eq '^KAKAO_REST_API=.+' .env; then
  skip_boot ".env에 KAKAO_SECRET 또는 KAKAO_REST_API 값 없음"
fi
if lsof -tiTCP:"$VERIFY_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  fail "검증용 포트 ${VERIFY_PORT} 이미 점유 중 (lsof -iTCP:${VERIFY_PORT} 로 확인)"
fi
echo "  OK (MySQL up, .env ok, port ${VERIFY_PORT} free)"

# ---------- stage 3: 실제 기동 + 헬스체크 ----------
echo "[3/3] boot health check on port ${VERIFY_PORT}"

BOOT_JAR="$(ls build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -1)"
[[ -n "$BOOT_JAR" ]] || fail "실행 가능한 jar를 build/libs/ 에서 찾지 못함"

SERVER_PORT="$VERIFY_PORT" java -jar "$BOOT_JAR" > "$APP_LOG" 2>&1 &
APP_PID=$!

elapsed=0
while (( elapsed < BOOT_WAIT_MAX_SECONDS )); do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo ""
    echo "--- app log (tail 50) — 프로세스가 기동 중 종료됨 ---"
    tail -50 "$APP_LOG"
    APP_PID=""
    fail "application exited during startup"
  fi
  status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$HEALTH_URL" 2>/dev/null)"
  if [[ "$status" == "200" ]]; then
    echo "  OK (GET ${HEALTH_URL} -> 200, ${elapsed}s)"
    echo ""
    echo "=== VERIFY PASSED ==="
    exit 0
  fi
  sleep "$BOOT_POLL_INTERVAL"
  (( elapsed += BOOT_POLL_INTERVAL ))
done

echo ""
echo "--- app log (tail 50) — ${BOOT_WAIT_MAX_SECONDS}s 내 200 응답 없음 (마지막 상태: ${status:-none}) ---"
tail -50 "$APP_LOG"
fail "health check timeout"
