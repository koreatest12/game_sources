#!/usr/bin/env bash
set -euo pipefail

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "Run with sudo: sudo $0" >&2
  exit 1
fi

if [[ ! -r /etc/os-release ]]; then
  echo "Unsupported system: /etc/os-release not found" >&2
  exit 1
fi
. /etc/os-release
if [[ "${ID:-}" != "ubuntu" ]]; then
  echo "This installer targets Ubuntu. Detected: ${ID:-unknown}" >&2
  exit 1
fi

DEPLOY_USER=${DEPLOY_USER:-game-deploy}
DEPLOY_PUBLIC_KEY=${DEPLOY_PUBLIC_KEY:-}

apt-get update
apt-get install -y ca-certificates curl gnupg ufw rsync git openssl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker

if ! id "$DEPLOY_USER" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$DEPLOY_USER"
fi
usermod -aG docker "$DEPLOY_USER"

install -d -m 0755 -o "$DEPLOY_USER" -g "$DEPLOY_USER" /opt/game-sources
install -d -m 0700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh"

if [[ -n "$DEPLOY_PUBLIC_KEY" ]]; then
  AUTHORIZED_KEYS="/home/$DEPLOY_USER/.ssh/authorized_keys"
  touch "$AUTHORIZED_KEYS"
  grep -qxF "$DEPLOY_PUBLIC_KEY" "$AUTHORIZED_KEYS" || echo "$DEPLOY_PUBLIC_KEY" >> "$AUTHORIZED_KEYS"
  chown "$DEPLOY_USER:$DEPLOY_USER" "$AUTHORIZED_KEYS"
  chmod 600 "$AUTHORIZED_KEYS"
fi

docker --version
docker compose version

echo "Server dependencies installed."
echo "Deploy user: $DEPLOY_USER"
echo "Application directory: /opt/game-sources"
echo "Next: configure DNS, then run scripts/firewall-ufw.sh and deploy the production stack."
