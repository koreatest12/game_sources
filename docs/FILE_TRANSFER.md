# File Transfer Server

`game_sources` 서버에는 게임 리소스 제공 기능과 별도로 파일 생성·업로드·목록·다운로드·삭제 API가 포함됩니다.

## 환경 변수

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `ADMIN_TOKEN` | 없음 | 파일 관리 API 인증 토큰. 비어 있으면 파일 관리 API는 `503`으로 차단됩니다. |
| `FILE_STORAGE_DIR` | `data/files` | 서버 파일 저장 경로 |
| `MAX_UPLOAD_BYTES` | `104857600` | 파일 1개 최대 업로드 크기(기본 100 MiB) |
| `FILE_PUBLIC_DOWNLOADS` | `false` | `true`이면 `/files/{name}` 다운로드를 인증 없이 허용 |

운영에서는 충분히 긴 임의의 `ADMIN_TOKEN`을 사용하고 `FILE_PUBLIC_DOWNLOADS=false`를 권장합니다.

## 브라우저 화면

서버 실행 후 `http://localhost:8080/transfer.html`로 접속합니다.

- 파일 선택 또는 드래그앤드롭 업로드
- 텍스트 파일 생성/덮어쓰기
- 저장 파일 목록 및 SHA-256 확인
- 인증 다운로드
- 삭제

관리 토큰은 화면 메모리에만 사용하며 브라우저 로컬 저장소에 저장하지 않습니다.

## API

### 목록

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/api/files
```

### 파일 생성/업로드

```bash
curl -X PUT \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  --data-binary @./release.zip \
  http://localhost:8080/api/files/release.zip
```

동일한 이름이 있으면 임시 파일에 먼저 기록한 뒤 원자적 교체를 시도합니다.

### 텍스트 파일 생성

```bash
printf 'hello\n' | curl -X PUT \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  --data-binary @- \
  http://localhost:8080/api/files/hello.txt
```

### 메타데이터

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/api/files/release.zip
```

응답에는 파일명, 크기, 수정 시각, SHA-256, 다운로드 URL이 포함됩니다.

### 다운로드

기본 비공개 모드에서는 인증 헤더가 필요합니다.

```bash
curl -L \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -o release.zip \
  http://localhost:8080/files/release.zip
```

`Range: bytes=...` 단일 범위를 지원하므로 이어받기 가능한 클라이언트에서도 사용할 수 있습니다.

### 삭제

```bash
curl -X DELETE \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/api/files/release.zip
```

## Docker Compose

```bash
ADMIN_TOKEN='replace-with-a-long-random-token' docker compose up -d --build
```

`compose.yml`은 `file_data` 볼륨을 `/data/files`에 마운트하므로 컨테이너 재생성 후에도 파일이 유지됩니다.

운영용 `deployment/compose.production.yml`도 같은 영속 볼륨을 사용하며 애플리케이션 컨테이너의 루트 파일시스템은 계속 `read_only` 상태를 유지합니다.

## Render 주의사항

현재 `render.yaml`의 무료 Web Service 구성은 영속 디스크를 선언하지 않습니다. 따라서 Render에서 파일 전송 기능을 장기 저장 용도로 사용하려면 영속 디스크를 지원하는 플랜과 `/data/files` 마운트 구성을 추가해야 합니다. 무료/임시 파일시스템에서는 재배포나 인스턴스 교체 시 업로드 파일이 사라질 수 있습니다.

## 보안 제한

- `..`, 하위 디렉터리, 숨김 파일명, 제어문자 및 주요 비호환 문자를 거부합니다.
- 업로드 크기는 스트리밍 중에도 다시 검사합니다.
- 관리 API는 Bearer 토큰이 없으면 거부됩니다.
- 다운로드는 기본적으로 인증이 필요합니다.
- 파일 체크섬은 SHA-256으로 제공합니다.
