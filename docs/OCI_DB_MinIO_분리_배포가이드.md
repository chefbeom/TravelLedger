# OCI DB + MinIO 분리 배포 가이드

이 문서는 현재 프로젝트를 다음 2서버 구조로 분리할 때 바로 사용할 수 있는 설정 가이드입니다.

- 앱 서버: `3 OCPU / 6GB`
  - `backend`
  - `frontend`
  - 호스트 `nginx`
- 데이터 서버: `2 OCPU / 4GB`
  - `MariaDB`
  - `MinIO`

기존 [docker-compose.oci.yml](C:/Users/kjs99/Desktop/calen/docker-compose.oci.yml)은 그대로 두고, 아래 신규 파일만 사용합니다.

- 앱 서버용: [docker-compose.oci.app.yml](C:/Users/kjs99/Desktop/calen/docker-compose.oci.app.yml)
- 데이터 서버용: [docker-compose.oci.data.yml](C:/Users/kjs99/Desktop/calen/docker-compose.oci.data.yml)
- 앱 서버 환경파일 예시: [.env.oci.app.example](C:/Users/kjs99/Desktop/calen/.env.oci.app.example)
- 데이터 서버 환경파일 예시: [.env.oci.data.example](C:/Users/kjs99/Desktop/calen/.env.oci.data.example)

## 1. 준비 방향

핵심은 이렇습니다.

- 앱 서버는 DB와 MinIO를 더 이상 로컬 컨테이너로 띄우지 않습니다.
- 앱 서버의 `backend`는 **원격 MariaDB**와 **원격 MinIO**를 사용합니다.
- 데이터 서버는 `3306`, `9000`, `9001`을 열되, 가능하면 **사설망/NSG 기준으로 제한**합니다.

## 2. 권장 네트워크

권장 연결은 아래와 같습니다.

- 앱 서버 -> 데이터 서버
  - MariaDB `3306`
  - MinIO API `9000`
- 관리자 PC -> 데이터 서버
  - MinIO Console `9001` (필요할 때만)

권장 정책:

- `3306`: 앱 서버 사설 IP만 허용
- `9000`: 앱 서버 사설 IP만 허용
- `9001`: 관리자 IP만 허용

추가로, `presigned URL` 업로드를 유지하려면 브라우저가 접근할 수 있는 MinIO API 주소가 필요합니다.

- 내부 업로드만 쓸 경우:
  - 앱 서버 `.env`에서 `TRAVEL_PRESIGNED_UPLOAD_ENABLED=false`
- 직접 업로드를 유지할 경우:
  - `MINIO_PUBLIC_API`에 브라우저가 접근 가능한 주소 설정
  - `TRAVEL_PRESIGNED_UPLOAD_ENABLED=true`

실제 `MinIO presigned URL` 구성 절차는 별도 가이드로 분리해 두었습니다.

- [OCI_MinIO_presignedURL_설정가이드.md](C:/Users/kjs99/Desktop/calen/docs/OCI_MinIO_presignedURL_설정가이드.md)

## 3. 데이터 서버 준비

데이터 서버에서 레포를 두고 아래처럼 환경파일을 만듭니다.

```bash
cp .env.oci.data.example .env.oci.data
```

주요 값만 수정합니다.

- `DB_PASSWORD`
- `DB_ROOT_PASSWORD`
- `MINIO_ROOT_PASSWORD`
- `MINIO_CONSOLE_EXTERNAL_URL`

데이터 서버 실행:

```bash
docker compose --env-file .env.oci.data -f docker-compose.oci.data.yml up -d
```

정상 확인:

```bash
docker compose --env-file .env.oci.data -f docker-compose.oci.data.yml ps
```

## 4. 앱 서버 준비

앱 서버에서도 레포를 두고 아래처럼 환경파일을 만듭니다.

```bash
cp .env.oci.app.example .env.oci.app
```

주요 값만 수정합니다.

- `DB_PASSWORD`
- `DB_INTERNAL_HOST`
  - 데이터 서버 사설 IP
- `MINIO_ROOT_PASSWORD`
- `MINIO_API_INTERNAL_URL`
  - 예: `http://10.0.0.20:9000`
- `MINIO_PUBLIC_API`
  - 예: `https://minio.example.com`
- `TRAVEL_PRESIGNED_UPLOAD_ENABLED`
  - MinIO 공개 업로드 경로를 열었을 때만 `true`

앱 서버 실행:

```bash
docker compose --env-file .env.oci.app -f docker-compose.oci.app.yml up -d --build
```

정상 확인:

```bash
docker compose --env-file .env.oci.app -f docker-compose.oci.app.yml ps
```

## 5. Nginx 유지

기존 Nginx 설정은 그대로 유지해도 됩니다.

- 앱 서버의 `127.0.0.1:8081` -> 프런트
- 앱 서버의 `127.0.0.1:8080` -> 백엔드

즉 기존 [docker-compose.oci.yml](C:/Users/kjs99/Desktop/calen/docker-compose.oci.yml) 기반 Nginx 프록시 구조는 바뀌지 않습니다.

## 6. 백업/복구 영향

현재 프로젝트의 관리자 데이터 백업/복구는 앱 서버의 백엔드 컨테이너에서 수행됩니다.

분리 후에도 그대로 동작하려면:

- 앱 서버에서 데이터 서버 `3306` 접근 가능
- 앱 서버에서 MinIO `9000` 접근 가능
- 앱 서버에 `rclone` 설정과 `/opt/calen-backup` 유지

즉 DB와 MinIO를 분리해도 백업/복구 로직 자체는 그대로 유지됩니다.

## 7. Presigned 업로드 체크

여행 사진 직접 업로드를 유지하려면 아래 2개가 함께 맞아야 합니다.

- 앱 서버 `.env.oci.app`
  - `TRAVEL_PRESIGNED_UPLOAD_ENABLED=true`
  - `MINIO_PUBLIC_API=https://...`
- 데이터 서버
  - 브라우저가 접근 가능한 MinIO API 주소 제공

공개 주소가 아직 없다면, 먼저는 아래처럼 두는 편이 안전합니다.

```env
TRAVEL_PRESIGNED_UPLOAD_ENABLED=false
MINIO_PUBLIC_API=
```

이 경우 업로드는 기존 서버 중계 방식으로 계속 동작합니다.

## 8. 점검 순서

1. 데이터 서버 먼저 실행
2. MariaDB health 확인
3. MinIO health 확인
4. 앱 서버 실행
5. 웹 로그인 확인
6. 가계부 조회/저장 확인
7. 여행 사진 업로드 확인
8. 관리자 백업/복구 모달 확인

## 9. 빠른 확인 명령

데이터 서버:

```bash
docker compose --env-file .env.oci.data -f docker-compose.oci.data.yml ps
docker compose --env-file .env.oci.data -f docker-compose.oci.data.yml logs --tail=100 mariadb
docker compose --env-file .env.oci.data -f docker-compose.oci.data.yml logs --tail=100 minio
```

앱 서버:

```bash
docker compose --env-file .env.oci.app -f docker-compose.oci.app.yml ps
docker compose --env-file .env.oci.app -f docker-compose.oci.app.yml logs --tail=100 backend
docker compose --env-file .env.oci.app -f docker-compose.oci.app.yml logs --tail=100 frontend
```

## 10. 요약

이 프로젝트 규모에서는 아래 구성이 가장 현실적입니다.

- 앱 서버 `3 / 6`
- 데이터 서버 `2 / 4`

이 분리 방식의 장점:

- Java backend 메모리 압박 감소
- MariaDB/MinIO와 앱 서버 역할 분리
- 장애 원인 파악이 쉬움
- 현재 기능 구조를 거의 바꾸지 않고 이전 가능
