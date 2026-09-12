#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${PROD_HOST:?PROD_HOST is required}"
: "${PROD_USER:?PROD_USER is required}"
: "${PROD_SSH_KEY:?PROD_SSH_KEY path is required}"
: "${DOMAIN:?DOMAIN is required}"
: "${ACME_EMAIL:?ACME_EMAIL is required}"
: "${ADMIN_TOKEN:?ADMIN_TOKEN is required}"

SSH_PORT=${SSH_PORT:-22}
REMOTE_DIR=${REMOTE_DIR:-/opt/game-sources}
SSH=(ssh -i "$PROD_SSH_KEY" -p "$SSH_PORT" -o BatchMode=yes -o StrictHostKeyChecking=accept-new)
RSYNC_RSH="ssh -i $PROD_SSH_KEY -p $SSH_PORT -o BatchMode=yes -o StrictHostKeyChecking=accept-new"

command -v rsync >/dev/null 2>&1 || { echo "rsync is required" >&2; exit 1; }

"${SSH[@]}" "$PROD_USER@$PROD_HOST" "mkdir -p '$REMOTE_DIR/deployment'"
rsync -az --delete \
  --exclude '.git/' \
  --exclude 'target/' \
  --exclude '.local-secrets/' \
  --exclude 'deployment/prod.env' \
  --exclude 'data/files/' \
  -e "$RSYNC_RSH" \
  "$ROOT_DIR/" "$PROD_USER@$PROD_HOST:$REMOTE_DIR/"

ENV_FILE=$(mktemp)
trap 'rm -f "$ENV_FILE"' EXIT
chmod 600 "$ENV_FILE"
cat > "$ENV_FILE" <<EOF
DOMAIN=$DOMAIN
ACME_EMAIL=$ACME_EMAIL
ADMIN_TOKEN=$ADMIN_TOKEN
MAX_UPLOAD_BYTES=${MAX_UPLOAD_BYTES:-104857600}
FILE_PUBLIC_DOWNLOADS=${FILE_PUBLIC_DOWNLOADS:-false}
GAME_IMAGE=${GAME_IMAGE:-game-sources:prod}
JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS:--XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError}
SSH_PORT=$SSH_PORT
EOF

scp -q -i "$PROD_SSH_KEY" -P "$SSH_PORT" -o BatchMode=yes -o StrictHostKeyChecking=accept-new \
  "$ENV_FILE" "$PROD_USER@$PROD_HOST:$REMOTE_DIR/deployment/prod.env"

"${SSH[@]}" "$PROD_USER@$PROD_HOST" \
  "chmod 600 '$REMOTE_DIR/deployment/prod.env' && cd '$REMOTE_DIR' && docker compose --env-file deployment/prod.env -f deployment/compose.production.yml up -d --build --remove-orphans && docker compose --env-file deployment/prod.env -f deployment/compose.production.yml ps"

for attempt in {1..24}; do
  if curl -fsS --max-time 5 "https://$DOMAIN/health" | grep -q '"status":"UP"'; then
    echo "Production deployment healthy: https://$DOMAIN"
    exit 0
  fi
  echo "Waiting for HTTPS health check ($attempt/24)..."
  sleep 5
done

echo "Deployment started but public HTTPS health check did not become ready." >&2
echo "Check DNS A/AAAA records, ports 80/443, and: docker logs game-sources-caddy" >&2
exit 1
