#!/usr/bin/env bash
set -euo pipefail

: "${DOMAIN:?DOMAIN is required, e.g. DOMAIN=game.example.com}"
BASE="https://$DOMAIN"

echo "== DNS =="
getent ahosts "$DOMAIN" | head -n 10 || true

echo "== TLS / headers =="
curl -fsSI "$BASE/" | sed -n '1,20p'

echo "== Health =="
curl -fsS "$BASE/health"
echo

echo "== Ready =="
curl -fsS "$BASE/ready"
echo

echo "== Status =="
curl -fsS "$BASE/api/status"
echo

echo "== Game resources =="
for path in / /static/styles.css /static/game.js /assets/logo.svg /assets/player.svg /assets/enemy.svg /assets/gem.svg /assets/background.svg; do
  code=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE$path")
  printf '%-28s %s\n' "$path" "$code"
  test "$code" = "200"
done

if [[ -n ${ADMIN_TOKEN:-} ]]; then
  echo "== Protected metrics =="
  unauth=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/metrics")
  test "$unauth" = "401"
  curl -fsS -H "Authorization: Bearer $ADMIN_TOKEN" "$BASE/metrics"
  echo
fi

echo "Production verification passed: $BASE"
