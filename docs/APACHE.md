# Apache HTTP Server integration

`game_sources`는 Apache HTTP Server를 Java 애플리케이션 앞단의 reverse proxy로 사용할 수 있도록 구성되어 있습니다.

## Version

- Base image: `httpd:2.4.68-alpine`
- Configuration: `deployment/apache/game-sources.conf`
- Image definition: `deployment/apache/Dockerfile`

## Architecture

### Local Docker Compose

```text
Browser / API client
        |
        v
Apache :8080
        |
        v
Java game-server :8080
        |
        v
file_data volume (/data/files)
```

### Production VPS

```text
Internet :80/:443
        |
        v
Caddy (TLS / automatic HTTPS)
        |
        v
Apache :8080
        |
        v
Java game-server :8080
        |
        v
file_data volume (/data/files)
```

Caddy는 공개 TLS 종료와 인증서 자동 갱신을 담당하고, Apache는 HTTP reverse proxy 계층을 담당합니다.

## Local start

Linux/macOS:

```bash
export ADMIN_TOKEN='replace-with-a-long-random-token'
docker compose up -d --build
curl -i http://localhost:8080/health
```

PowerShell:

```powershell
$env:ADMIN_TOKEN="replace-with-a-long-random-token"
docker compose up -d --build
curl.exe -i http://localhost:8080/health
```

브라우저:

- Game: `http://localhost:8080/`
- File transfer: `http://localhost:8080/transfer.html`
- Health: `http://localhost:8080/health`

## Apache configuration

Apache는 다음 핵심 설정을 사용합니다.

- `ProxyRequests Off`: open forward proxy 비활성화
- `ProxyPreserveHost On`: 원래 Host 헤더 유지
- `ProxyPass` / `ProxyPassReverse`: Java 컨테이너로 reverse proxy
- `AllowEncodedSlashes NoDecode`: URL 인코딩된 파일 이름을 불필요하게 디코딩하지 않음
- `nocanon`: 파일 API 경로를 가능한 원형 그대로 upstream에 전달
- `ServerTokens Prod`, `ServerSignature Off`: 서버 정보 노출 축소
- `TraceEnable Off`: HTTP TRACE 비활성화

Bearer `Authorization`, HTTP `Range`, 업로드 request body는 Apache를 통과해 기존 Java 파일 전송 API로 전달됩니다.

## Production

`deployment/compose.production.yml`은 다음 세 서비스를 실행합니다.

1. `game-server` — Java 21 애플리케이션 / 파일 저장
2. `apache` — Apache HTTP Server reverse proxy
3. `caddy` — 공개 HTTPS/TLS endpoint

배포 후 확인:

```bash
curl -fsS https://$DOMAIN/health
curl -fsS https://$DOMAIN/ready
curl -fsS https://$DOMAIN/transfer.html | grep 'Game Sources File Transfer'
```

인증 파일 API 확인:

```bash
printf 'apache-proxy-test' > /tmp/apache-test.txt
curl -fsS -X PUT \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  --data-binary @/tmp/apache-test.txt \
  "https://$DOMAIN/api/files/apache-test.txt"

curl -fsS \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  "https://$DOMAIN/files/apache-test.txt"
```

## Validation

GitHub Actions Maven CI에서 다음을 검증하도록 구성합니다.

- Apache image build
- `httpd -t` configuration syntax validation
- Docker Compose rendering
- Apache를 통한 `/health`, 게임 화면, 파일 전송 화면 접근
- Apache를 통한 authenticated upload/download
- Apache를 통한 HTTP Range download
- Java 및 Apache container health status
