# game_sources

Java 21 + Maven 기반 게임 소스 및 HTTP 서버 프로젝트입니다.

## Build environment

- Java: 21
- Apache Maven: 3.9.16
- Maven Wrapper metadata: 3.3.4 (`only-script`)
- Default server port: `8080`

## Project structure

```text
.
├─ .github/workflows/maven.yml
├─ .mvn/wrapper/
│  ├─ MavenWrapper.ps1
│  └─ maven-wrapper.properties
├─ src/
│  ├─ main/
│  │  ├─ java/io/github/koreatest12/game/GameServer.java
│  │  └─ resources/index.html
│  └─ test/
│     ├─ java/
│     └─ resources/
├─ Dockerfile
├─ compose.yml
├─ mvnw
├─ mvnw.cmd
└─ pom.xml
```

## Maven cache

GitHub Actions는 `actions/setup-java`의 Maven 캐시 기능을 사용하여 `~/.m2/repository`를 재사용합니다. `pom.xml`을 캐시 의존성 기준으로 사용하고, 빌드 전에 다음 명령으로 필요한 Maven 플러그인과 의존성을 미리 받아 캐시를 채웁니다.

```bash
sh ./mvnw -B -ntp dependency:go-offline
```

로컬에서도 Maven이 설치되어 있지 않으면 Wrapper가 Maven 3.9.16을 `.m2/wrapper/dists` 아래에 설치한 뒤 실행합니다.

## Build

### Windows

```powershell
.\mvnw.cmd -version
.\mvnw.cmd clean verify
```

### Linux / macOS

```bash
sh ./mvnw -version
sh ./mvnw clean verify
```

빌드 결과 실행 JAR:

```text
target/game-sources.jar
```

## Start server

먼저 빌드합니다.

```bash
sh ./mvnw -B -ntp clean package
java -jar target/game-sources.jar
```

Windows PowerShell:

```powershell
.\mvnw.cmd -B -ntp clean package
java -jar target\game-sources.jar
```

기본 접속 주소:

```text
http://localhost:8080/
```

환경변수로 바인딩 주소와 포트를 변경할 수 있습니다.

```bash
HOST=0.0.0.0 PORT=9090 java -jar target/game-sources.jar
```

## Server endpoints

- `GET /` - 서버 기본 페이지
- `GET /health` - 상태 확인, 정상 시 `{"status":"UP"}`
- `GET /api/status` - 서비스명, 실행 상태, 현재 서버 시간 반환

## Docker server

빌드 및 실행:

```bash
docker compose up --build -d
```

상태 확인:

```bash
curl http://localhost:8080/health
```

종료:

```bash
docker compose down
```

컨테이너는 `restart: unless-stopped`로 구성되어 있고 Docker health check도 `/health`를 사용합니다.

## CI verification

Pull Request 및 `main` push 시 GitHub Actions가 다음 순서로 검증합니다.

1. Java 21 설치 및 Maven 캐시 복원
2. `dependency:go-offline`으로 Maven 캐시 예열
3. `mvn verify` 빌드
4. 생성된 JAR 서버 기동
5. `/health` HTTP smoke test
6. Docker 서버 이미지 빌드
