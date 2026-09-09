# game_sources

Java 21 + Maven 기반의 **실행 가능한 브라우저 게임 서버**입니다. 단순 health-check 샘플이 아니라 서버가 HTML/CSS/JavaScript/SVG 게임 리소스를 직접 제공하며, JAR·Docker·HTTPS 운영 배포와 GitHub Actions CI/CD 구성을 포함합니다.

## Included

- Java 21 내장 HTTP 서버 + Virtual Threads
- 브라우저 플레이 게임 **Game Sources Arena**
- 플레이어/적/보석/배경/로고 SVG 리소스
- `/health`, `/ready`, `/api/status`, `/metrics`
- `ADMIN_TOKEN` Bearer 인증 기반 metrics 보호
- Maven 3.9.16 Wrapper + Maven dependency cache
- 실행 JAR `target/game-sources.jar`
- Docker 이미지 + 로컬 Compose
- 운영 Docker Compose + Caddy 자동 HTTPS
- UFW 방화벽 스크립트
- Ubuntu Docker 서버 설치 스크립트
- Ed25519 SSH 배포키 + 랜덤 ADMIN_TOKEN 생성기
- SSH/rsync 영구 원격 배포 스크립트
- GitHub Actions Production Deploy
- GitHub Actions 다운로드 번들 및 Release 생성
- SHA-256 체크섬

## Project layout

```text
.
├─ .github/workflows/
│  ├─ maven.yml
│  ├─ deploy.yml
│  └─ release.yml
├─ deployment/
│  ├─ Caddyfile
│  ├─ compose.production.yml
│  ├─ compose.prod.yml
│  ├─ game-sources.service
│  └─ *.env.example
├─ docs/
│  └─ DEPLOYMENT.md
├─ scripts/
│  ├─ deploy-remote.sh
│  ├─ firewall-ufw.sh
│  ├─ generate-deployment-secrets.sh
│  ├─ install-ubuntu.sh
│  └─ verify-production.sh
├─ src/main/java/io/github/koreatest12/game/GameServer.java
├─ src/main/resources/
│  ├─ index.html
│  ├─ static/
│  │  ├─ game.js
│  │  └─ styles.css
│  └─ assets/
│     ├─ logo.svg
│     ├─ player.svg
│     ├─ enemy.svg
│     ├─ gem.svg
│     └─ background.svg
├─ Dockerfile
├─ compose.yml
├─ pom.xml
├─ mvnw
└─ mvnw.cmd
```

## Local run — Windows

```powershell
.\mvnw.cmd -B -ntp clean package
java -jar target\game-sources.jar
```

Open `http://localhost:8080/`.

## Local run — Linux/macOS

```bash
sh ./mvnw -B -ntp clean package
java -jar target/game-sources.jar
```

## Docker run

```bash
docker compose up --build -d
curl http://localhost:8080/health
```

Stop:

```bash
docker compose down
```

## Server endpoints

| Endpoint | Purpose | Auth |
|---|---|---|
| `/` | Playable Game Sources Arena | Public |
| `/health` | Liveness | Public |
| `/ready` | Readiness | Public |
| `/api/status` | Runtime status | Public |
| `/metrics` | Runtime request/uptime data | Bearer token when `ADMIN_TOKEN` is configured |
| `/static/*` | CSS/JavaScript | Public |
| `/assets/*` | Game images/resources | Public |

## Maven cache

GitHub Actions uses `actions/setup-java` Maven cache keyed from `pom.xml` and runs:

```bash
sh ./mvnw -B -ntp dependency:go-offline
```

before verification so the dependency/plugin cache is populated and reused on later runs.

## CI is a real running-server test

`.github/workflows/maven.yml` does more than a single smoke request:

1. Maven dependency cache warm-up
2. `mvn verify`
3. Starts the real executable JAR
4. Requests the game page, CSS, JavaScript and SVG assets
5. Verifies health/readiness/status APIs
6. Verifies unauthorized and authorized `/metrics`
7. Builds the Docker image
8. Starts the Docker container and waits for Docker health = `healthy`
9. Tests the running Docker game server
10. Builds a downloadable server bundle and SHA-256 file

CI servers are intentionally temporary. A 24/7 public service is deployed through the production workflow below.

## 24/7 production deployment

See **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)** for the complete server, key, DNS, HTTPS, firewall, deployment and key-rotation runbook.

High-level flow:

```bash
DOMAIN=game.example.com ACME_EMAIL=admin@example.com \
  bash scripts/generate-deployment-secrets.sh

# bootstrap Ubuntu server
# configure DNS A/AAAA
# apply firewall only after confirming the SSH port
# deploy persistently
bash scripts/deploy-remote.sh
```

Production topology:

```text
Internet
   │
   ├─ TCP 80 ──────┐
   ├─ TCP 443 ─────┼── Caddy ── private Docker network ── Java game-server:8080
   └─ UDP 443 ─────┘

SSH: configurable/rate-limited
8080: NOT publicly published
```

Caddy terminates HTTPS and stores certificate state in persistent Docker volumes. The application and Caddy use `restart: unless-stopped` for persistent service operation across process restarts and normal host reboots after Docker starts.

## GitHub production secrets

The `production` environment uses:

- `PROD_HOST`
- `PROD_USER`
- `PROD_SSH_KEY`
- `PROD_SSH_PORT` (optional)
- `PROD_DOMAIN`
- `ACME_EMAIL`
- `ADMIN_TOKEN`

Private key/token values must be stored as GitHub Secrets, never committed.

## Downloads

Every successful main CI run uploads a `game-server-bundle` artifact. `Build Release` can publish versioned GitHub Releases containing:

- `game-sources.jar`
- `game-sources.jar.sha256`
- `game-server-bundle.tar.gz`
- `game-server-bundle.tar.gz.sha256`

Verify downloaded bundles:

```bash
sha256sum -c game-server-bundle.tar.gz.sha256
```

## Production verification

```bash
DOMAIN=game.example.com ADMIN_TOKEN='your-token' bash scripts/verify-production.sh
```

This validates DNS visibility, TLS/headers, health, readiness, runtime status, game assets and authenticated metrics.
