# Production deployment runbook

This repository supports a persistent Ubuntu server using Docker Compose + Caddy.
The Java application is private on port 8080. Only Caddy publishes 80/443.

## 1. Requirements

- Ubuntu server with a public IPv4/IPv6 address
- A domain you control
- DNS A/AAAA record pointing the domain to the server
- TCP 22 (or your SSH port), TCP 80, TCP/UDP 443 reachable at the cloud/network firewall
- Local `ssh-keygen`, `openssl`, `ssh`, `scp`, `rsync`

Do not commit private keys, `ADMIN_TOKEN`, or `deployment/prod.env`.

## 2. Generate deployment keys and runtime token

```bash
DOMAIN=game.example.com ACME_EMAIL=admin@example.com \
  bash scripts/generate-deployment-secrets.sh
```

Outputs under `.local-secrets/`:

- `game_sources_deploy_ed25519`: SSH private key
- `game_sources_deploy_ed25519.pub`: SSH public key
- `prod.env`: domain, ACME email, random ADMIN_TOKEN and runtime settings

The directory is ignored by Git.

## 3. Bootstrap the Ubuntu server

On your workstation, read the generated public key and run the installer on the server.
You can pass the public key through a secure shell session:

```bash
export PUBKEY="$(cat .local-secrets/game_sources_deploy_ed25519.pub)"
ssh root@SERVER_IP "DEPLOY_USER=game-deploy DEPLOY_PUBLIC_KEY='$PUBKEY' bash -s" < scripts/install-ubuntu.sh
```

The installer uses Docker's official Ubuntu APT repository and installs Docker Engine, Buildx, Compose, UFW, rsync, Git, OpenSSL and required CA tooling. It creates `/opt/game-sources` and the `game-deploy` account.

## 4. Configure network and firewall

First confirm the actual SSH port. A wrong value can lock you out.

```bash
ssh root@SERVER_IP 'CONFIRM_FIREWALL=YES SSH_PORT=22 bash -s' < scripts/firewall-ufw.sh
```

Host policy:

| Port | Protocol | Purpose |
|---|---|---|
| 22 (configurable) | TCP | SSH, rate-limited |
| 80 | TCP | HTTP redirect and ACME validation |
| 443 | TCP | HTTPS |
| 443 | UDP | HTTP/3 / QUIC |
| 8080 | none public | Java app, Docker internal network only |

If your VPS/cloud provider has a separate security group/network firewall, apply the same inbound rules there. Do not create a public 8080 rule.

## 5. DNS and HTTPS

Create an A record (and AAAA if IPv6 is used) for your production hostname pointing to the server. Caddy reads `DOMAIN` and `ACME_EMAIL`, obtains/renews the public TLS certificate and reverse-proxies traffic to `game-server:8080`.

DNS must be correct and public ports 80/443 must be reachable before certificate issuance can succeed.

## 6. Deploy from a workstation

```bash
set -a
source .local-secrets/prod.env
set +a
export PROD_HOST=SERVER_IP
export PROD_USER=game-deploy
export PROD_SSH_KEY="$PWD/.local-secrets/game_sources_deploy_ed25519"
bash scripts/deploy-remote.sh
```

The script:

1. Syncs repository files to `/opt/game-sources` over SSH/rsync.
2. Transfers `deployment/prod.env` with mode 0600.
3. Runs `docker compose ... up -d --build --remove-orphans`.
4. Keeps the application and Caddy running with `restart: unless-stopped`.
5. Polls the public `https://DOMAIN/health` endpoint and fails if production never becomes healthy.

## 7. Deploy from GitHub Actions

Create a GitHub Environment named `production` and add these secrets:

- `PROD_HOST`
- `PROD_USER`
- `PROD_SSH_KEY` (complete private-key text)
- `PROD_SSH_PORT` (optional; default 22)
- `PROD_DOMAIN`
- `ACME_EMAIL`
- `ADMIN_TOKEN`

Then run **Actions → Production Deploy → Run workflow**.
The workflow performs a persistent remote deployment and verifies HTTPS, the game page, health endpoint and protected metrics endpoint.

## 8. Operations

```bash
cd /opt/game-sources
set -a; source deployment/prod.env; set +a

docker compose --env-file deployment/prod.env -f deployment/compose.production.yml ps
docker compose --env-file deployment/prod.env -f deployment/compose.production.yml logs -f --tail=200
docker compose --env-file deployment/prod.env -f deployment/compose.production.yml restart game-server
docker compose --env-file deployment/prod.env -f deployment/compose.production.yml pull caddy
docker compose --env-file deployment/prod.env -f deployment/compose.production.yml up -d --build
```

Public checks:

```bash
curl -fsS https://game.example.com/health
curl -fsS https://game.example.com/api/status
curl -fsS -H "Authorization: Bearer ADMIN_TOKEN" https://game.example.com/metrics
```

## 9. Downloads

Every successful main CI run uploads `game-server-bundle` for 30 days. The bundle contains the executable JAR, Docker files, deployment configuration, server scripts, README and license plus a SHA-256 checksum.

`Build Release` can create a GitHub Release such as `v1.0.0` containing:

- `game-sources.jar`
- `game-sources.jar.sha256`
- `game-server-bundle.tar.gz`
- `game-server-bundle.tar.gz.sha256`

Always verify the checksum after downloading.

## 10. Key rotation

Generate a new key/token into a new output directory, add the new SSH public key to the server, update GitHub production secrets, deploy successfully, then remove the old authorized key. Never delete the old key before verifying the replacement works.
