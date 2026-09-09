#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "Run with sudo: sudo CONFIRM_FIREWALL=YES SSH_PORT=22 $0" >&2
  exit 1
fi

SSH_PORT=${SSH_PORT:-22}
if ! [[ "$SSH_PORT" =~ ^[0-9]+$ ]] || (( SSH_PORT < 1 || SSH_PORT > 65535 )); then
  echo "Invalid SSH_PORT: $SSH_PORT" >&2
  exit 2
fi

cat <<EOF
Firewall policy to apply:
- default deny incoming
- default allow outgoing
- rate-limit SSH on TCP/$SSH_PORT
- allow HTTP TCP/80
- allow HTTPS TCP/443
- allow HTTP/3 QUIC UDP/443
- application port 8080 is NOT opened publicly
EOF

if [[ ${CONFIRM_FIREWALL:-NO} != "YES" ]]; then
  echo "No changes made. Re-run with CONFIRM_FIREWALL=YES after confirming SSH_PORT." >&2
  exit 3
fi

command -v ufw >/dev/null 2>&1 || { apt-get update && apt-get install -y ufw; }

ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw limit "$SSH_PORT/tcp" comment 'SSH rate limit'
ufw allow 80/tcp comment 'HTTP ACME redirect'
ufw allow 443/tcp comment 'HTTPS'
ufw allow 443/udp comment 'HTTP3 QUIC'
ufw --force enable
ufw status verbose

echo "Firewall enabled. Docker production compose publishes only 80/443; app port 8080 remains internal."
