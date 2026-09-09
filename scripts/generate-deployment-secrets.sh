#!/usr/bin/env bash
set -euo pipefail

umask 077
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT_DIR=${OUT_DIR:-"$ROOT_DIR/.local-secrets"}
DOMAIN=${DOMAIN:-}
ACME_EMAIL=${ACME_EMAIL:-}

if [[ -z "$DOMAIN" || -z "$ACME_EMAIL" ]]; then
  echo "Usage: DOMAIN=game.example.com ACME_EMAIL=admin@example.com $0" >&2
  exit 2
fi

for command_name in openssl ssh-keygen; do
  command -v "$command_name" >/dev/null 2>&1 || { echo "$command_name is required" >&2; exit 1; }
done

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

ADMIN_TOKEN=$(openssl rand -hex 32)
cat > "$OUT_DIR/prod.env" <<EOF
DOMAIN=$DOMAIN
ACME_EMAIL=$ACME_EMAIL
ADMIN_TOKEN=$ADMIN_TOKEN
GAME_IMAGE=game-sources:prod
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError
SSH_PORT=${SSH_PORT:-22}
EOF
chmod 600 "$OUT_DIR/prod.env"

KEY_FILE="$OUT_DIR/game_sources_deploy_ed25519"
if [[ ! -f "$KEY_FILE" ]]; then
  ssh-keygen -q -t ed25519 -a 100 -N "" -C "game-sources-deploy@$DOMAIN" -f "$KEY_FILE"
fi
chmod 600 "$KEY_FILE"
chmod 644 "$KEY_FILE.pub"

cat <<EOF
Created deployment material in: $OUT_DIR
- Runtime environment: $OUT_DIR/prod.env (0600)
- SSH private key:    $KEY_FILE (0600)
- SSH public key:     $KEY_FILE.pub

Add ONLY the public key below to the server's deploy account:
$(cat "$KEY_FILE.pub")

Never commit or upload prod.env or the private key to the repository.
For GitHub Actions deployment, store the private key content in PROD_SSH_KEY,
and store DOMAIN/ACME_EMAIL/ADMIN_TOKEN as repository or environment secrets.
EOF
