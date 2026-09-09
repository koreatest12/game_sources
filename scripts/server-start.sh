#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${RUN_DIR:-$ROOT_DIR/.run}"
PID_FILE="${PID_FILE:-$RUN_DIR/game-sources.pid}"
LOG_FILE="${LOG_FILE:-$RUN_DIR/game-sources.log}"
JAR_FILE="${JAR_FILE:-$ROOT_DIR/target/game-sources.jar}"
HOST="${HOST:-0.0.0.0}"
PORT="${PORT:-8080}"
READY_URL="${READY_URL:-http://127.0.0.1:${PORT}/ready}"

mkdir -p "$RUN_DIR"

if [[ -f "$PID_FILE" ]]; then
  old_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [[ -n "$old_pid" ]] && kill -0 "$old_pid" 2>/dev/null; then
    if curl -fsS --connect-timeout 2 "$READY_URL" | grep -q '"status":"UP"'; then
      echo "game-sources already running: pid=$old_pid url=$READY_URL"
      exit 0
    fi
    echo "Stopping stale/unready process pid=$old_pid"
    kill "$old_pid" 2>/dev/null || true
    sleep 1
  fi
  rm -f "$PID_FILE"
fi

if [[ ! -f "$JAR_FILE" ]]; then
  echo "JAR not found; building with Maven..."
  (cd "$ROOT_DIR" && sh ./mvnw -B -ntp package)
fi

echo "Starting game-sources on ${HOST}:${PORT}"
nohup env HOST="$HOST" PORT="$PORT" ADMIN_TOKEN="${ADMIN_TOKEN:-}" \
  java -jar "$JAR_FILE" >"$LOG_FILE" 2>&1 < /dev/null &
pid=$!
echo "$pid" > "$PID_FILE"

for attempt in $(seq 1 60); do
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "Server process exited before becoming ready" >&2
    cat "$LOG_FILE" >&2 || true
    rm -f "$PID_FILE"
    exit 1
  fi

  if curl -fsS --connect-timeout 2 --max-time 3 "$READY_URL" 2>/dev/null | grep -q '"status":"UP"'; then
    echo "game-sources ready: pid=$pid url=$READY_URL"
    exit 0
  fi
  sleep 1
done

echo "Server did not become ready within 60 seconds" >&2
cat "$LOG_FILE" >&2 || true
kill "$pid" 2>/dev/null || true
rm -f "$PID_FILE"
exit 1
