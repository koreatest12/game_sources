# game_sources

Java 게임 소스 저장소를 Maven 기반으로 구성한 프로젝트입니다.

## Build environment

- Java: 21
- Apache Maven: 3.9.16
- Maven Wrapper metadata: 3.3.4 (`only-script`)

## Maven directory structure

```text
.
├─ .github/workflows/maven.yml
├─ .mvn/wrapper/
│  ├─ MavenWrapper.ps1
│  └─ maven-wrapper.properties
├─ src/
│  ├─ main/
│  │  ├─ java/
│  │  └─ resources/
│  └─ test/
│     ├─ java/
│     └─ resources/
├─ mvnw
├─ mvnw.cmd
└─ pom.xml
```

## Windows

```powershell
.\mvnw.cmd -version
.\mvnw.cmd clean verify
```

Maven이 설치되어 있지 않아도 Wrapper가 Maven 3.9.16을 사용자 `.m2/wrapper/dists` 아래에 내려받고 SHA-512를 검증한 뒤 실행합니다. Java 21 JDK는 먼저 설치되어 있어야 합니다.

## Linux / macOS

```bash
sh ./mvnw -version
sh ./mvnw clean verify
```

## Source locations

- Java game source: `src/main/java`
- Game resources/assets configuration: `src/main/resources`
- Unit tests: `src/test/java`
- Test resources: `src/test/resources`

## CI

Pull Request 및 `main` 브랜치 push 시 GitHub Actions가 Java 21 환경에서 `mvn verify`를 자동 실행합니다.
