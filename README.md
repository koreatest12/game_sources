# game_sources

Java 21 + Maven 기반의 **실행 가능한 브라우저 게임 서버**입니다. 서버가 HTML/CSS/JavaScript/SVG 게임 리소스를 직접 제공하며, JAR·Docker·Render·VPS/HTTPS 운영 배포와 GitHub Actions CI/CD 구성을 포함합니다.

## Included

- Java 21 내장 HTTP 서버 + Virtual Threads
- 브라우저 플레이 게임 **Game Sources Arena**
- 플레이어/적/보석/배경/로고 SVG 리소스
- `/health`, `/ready`, `/api/status`, `/metrics`
- `ADMIN_TOKEN` Bearer 인증 기반 metrics 보호
- Maven 3.9.16 Wrapper + Maven dependency cache
- 실행 JAR `target/game-sources.jar`
- PID/로그/readiness 기반 장기 실행 시작·중지·상태 스크립트
- Docker 이미지 + 로컬 Compose
- **Render Blueprint (`render.yaml`) 기반 실제 외부 호스팅**
- 운영 VPS Compose + Caddy 자동 HTTPS
- UFW 방화벽 및 Ubuntu Docker 설치 스크립트
- Ed25519 SSH 배포키 + 랜덤 ADMIN_TOKEN 생성기
- SSH/rsync 영구 원격 배포 스크립트
- GitHub Actions Production Deploy: `auto` / `render` / `vps`
- 다운로드 번들, Release, SHA-256 체크섬

## Project layout

```text
.
├─ .github/workflows/
│  ├─ maven.yml
│  ├─ deploy.yml
│  └─ release.yml
├─ deployment/
├─ docs/
│  ├─ DEPLOYMENT.md
│  ├─ RENDER_DEPLOYMENT.md
│  ├─ DOWNLOADS.md
│  └─ SECURITY.md
├─ scripts/
│  ├─ server-start.sh
│  ├─ server-stop.sh
│  ├─ server-status.sh
│  ├─ deploy-remote.sh
│  ├─ firewall-ufw.sh
│  ├─ generate-deployment-secrets.sh
│  ├─ install-ubuntu.sh
│  └─ verify-production.sh
├─ src/main/java/io/github/koreatest12/game/GameServer.java
├─ src/main/resources/
├─ Dockerfile
├─ compose.yml
├─ render.yaml
├─ pom.xml
├─ mvnw
└─ mvnw.cmd
```

## Build

Windows:

```powershell
.\mvnw.cmd -B -ntp clean package
java -jar target\game-sources.jar
```

Linux/macOS:

```bash
sh ./mvnw -B -ntp clean package
java -jar target/game-sources.jar
```

Open `http://localhost:8080/`.

## Resilient long-running JAR start

Linux/Ubuntu/CI에서는 서버를 바로 background로 띄운 뒤 즉시 curl하지 않고 readiness까지 기다리는 스크립트를 사용할 수 있습니다.

```bash
ADMIN_TOKEN='change-me' PORT=8080 bash scripts/server-start.sh
bash scripts/server-status.sh
bash scripts/server-stop.sh
```

`server-start.sh`는 다음을 수행합니다.

1. 기존 PID 확인
2. JAR이 없으면 Maven package
3. nohup으로 서버 시작
4. 프로세스 생존 여부 확인
5. 최대 60초 동안 `/ready` 대기
6. 실패하면 서버 로그 출력 후 실패 처리

이 방식으로 서버가 뜨기 전에 `curl 127.0.0.1:8080`이 먼저 실행되는 race condition을 방지합니다.

## Docker run

```bash
docker compose up --build -d
curl http://localhost:8080/health
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

before verification so dependencies/plugins are populated and reused.

## CI runs the real server

`.github/workflows/maven.yml` performs Maven cache warm-up, `mvn verify`, Render/VPS configuration validation, real JAR startup with readiness waiting, game/API/resource/auth tests, Docker image build, real Docker health tests, and downloadable bundle generation.

## Actual hosted server without PROD_HOST — Render

The root `render.yaml` is the simplest external hosting path. It does **not** require `PROD_HOST`, SSH, UFW, Caddy, or a custom domain.

It defines a Docker web service with:

- Singapore region
- `/ready` health check
- public `onrender.com` subdomain
- CI-check-aware auto deploy
- platform-generated random `ADMIN_TOKEN`
- dynamic Render `PORT` support
- Free plan by default to avoid unexpected charges

See **[docs/RENDER_DEPLOYMENT.md](docs/RENDER_DEPLOYMENT.md)**.

> The Free plan is suitable for real deployment/testing but should not be treated as an always-on SLA. For continuous 24/7 production operation, explicitly upgrade the Render service to a paid instance type after accepting the cost.

If an existing Render service uses a deploy hook, add GitHub production secrets:

- `RENDER_DEPLOY_HOOK_URL`
- `RENDER_SERVICE_URL`

Then run **Production Deploy** with target `render` or `auto`.

## VPS production deployment

For a user-owned Ubuntu/VPS server, see **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)**.

VPS production topology:

```text
Internet
   │
   ├─ TCP 80 ──────┐
   ├─ TCP 443 ─────┼── Caddy ── private Docker network ── Java game-server:8080
   └─ UDP 443 ─────┘

SSH: configurable/rate-limited
8080: NOT publicly published
```

VPS GitHub production secrets:

- `PROD_HOST`
- `PROD_USER`
- `PROD_SSH_KEY`
- `PROD_SSH_PORT` (optional)
- `PROD_DOMAIN`
- `ACME_EMAIL`
- `ADMIN_TOKEN`

Private key/token values must be stored as GitHub Secrets, never committed.

## Production Deploy behavior

The workflow supports `auto`, `render`, and `vps`.

- `auto`: use configured Render deployment first, otherwise configured VPS
- `render`: deploy using `RENDER_DEPLOY_HOOK_URL`
- `vps`: deploy using SSH/rsync
- no configured target: **successful preflight with a clear configuration summary**, instead of a misleading `Missing production secret: PROD_HOST` server failure

## Downloads

Every successful main CI run uploads a `game-server-bundle` artifact containing the JAR, Docker/VPS files, Render Blueprint, scripts, docs, and SHA-256 file.

```bash
sha256sum -c game-server-bundle.tar.gz.sha256
```
