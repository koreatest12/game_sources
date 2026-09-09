#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${RUN_DIR:-$ROOT_DIR/.run}"
PID_FILE="${PID_FILE:-$RUN_DIR/game-sources.pid}"
PORT="${PORT:-8080}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:${PORT}/health}"

pid=""
[[ -f "$PID_FILE" ]] && pid="$(cat "$PID_FILE" 2>/dev/null || true)"

if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
  if curl -fsS --connect-timeout 2 --max-time 3 "$HEALTH_URL"; then
    echo
    echo "running pid=$pid url=$HEALTH_URL"
    exit 0
  fi
  echo "process exists but health check failed: pid=$pid" >&2
  exit 2
fi

echo "stopped"
exit 1
