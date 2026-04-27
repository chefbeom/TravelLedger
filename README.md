# TravelLedger (Calen)

TravelLedger는 가계부, 여행 기록, 여행 사진 지도, 드라이브형 파일 관리, 관리자 운영 기능을 하나로 묶은 개인 생활 기록 플랫폼입니다.

현재 프로젝트는 `Calen`이라는 내부 애플리케이션 이름을 함께 사용합니다.

## 주요 기능

### 메인 대시보드

- 가계부, 여행, 드라이브 상태를 한 화면에서 요약합니다.
- 팔레트 기반 대시보드로 각 위젯의 위치와 크기를 조정할 수 있습니다.
- 팔레트 배치는 사용자별로 저장되어 다른 기기에서도 이어서 사용할 수 있습니다.
- 빠른 거래 입력, 사진 액자, 드라이브 용량, 최근 파일, 여행 요약 같은 단축 위젯을 제공합니다.

### 가계부

- 달력 기반 거래 입력, 수정, 삭제, 조회를 지원합니다.
- 빠른 입력에서 금액 단축 버튼, 24시간제 시간 입력, 최근 분류/결제수단 기본값을 지원합니다.
- 달력, 빠른 입력, 사용자 설정 집계, 거래 시트 패널의 배치를 드래그와 리사이즈로 조정할 수 있습니다.
- 통계, 검색, 휴지통, 인사이트, 비교, 데이터 입출력, 분류 관리를 제공합니다.
- CSV 내보내기와 엑셀 가져오기 흐름을 유지합니다.

### 여행

- 여행 계획, 예산, 지출, 기록, 사진, 경로, GPX 기반 여행 데이터를 관리합니다.
- 여행 사진은 업로드 시 준비된 썸네일을 생성하고, 지도에서는 클러스터 또는 핀 형태로 볼 수 있습니다.
- 내 사진 탭에서 업로드한 사진을 시간순, 여행별, 지역별로 필터링해 볼 수 있습니다.
- 여행 공유 기능을 통해 공개 여행 기록을 커뮤니티형 지도와 목록으로 탐색할 수 있습니다.
- 지도 렌더링은 클러스터링과 viewport 중심 렌더링을 사용해 많은 사진 노드에서도 프론트엔드 부하를 줄입니다.

### 드라이브

- 파일과 폴더를 드라이브처럼 관리합니다.
- 최근 저장 파일, 용량, 공유 상태를 대시보드에서 빠르게 확인할 수 있습니다.
- 이미지 파일은 썸네일과 원본 열람 흐름을 분리합니다.

### 관리자

- 관리자 전용 대시보드에서 초대 링크, 데이터 백업, 복구, 운영 상태 확인 기능을 제공합니다.
- 일반 사용자 화면과 관리자 기능을 분리합니다.

## 기술 스택

### Frontend

- Vue 3
- JavaScript
- Vite
- Pinia
- GridStack
- Leaflet
- exifr

### Backend

- Java 17
- Spring Boot 3
- Spring MVC
- Spring Security
- Spring Data JPA
- Actuator

### Data / Storage

- MariaDB
- MinIO
- Redis Cache
- Redis State
- 로컬 파일 fallback 업로드 경로

### Infra

- Docker
- Docker Compose
- Nginx
- OCI
- Jenkins
- rclone 기반 외부 백업

### OCR / AI

- `PaddleOCR/`는 가계부 영수증/결제내역 이미지 분석을 위한 별도 사설 OCR 서비스입니다.
- 브라우저는 OCR 서버를 직접 호출하지 않고, 백엔드가 `POST /api/ledger/ocr/analyze`로 프록시 호출합니다.
- OCR 서버는 `X-OCR-API-Key`를 요구하며, 필요하면 내부에서 Gemma 호환 LLM을 호출해 OCR 원문을 거래 입력값으로 구조화합니다.
- 실제 영수증 이미지, OCR 로그, Python 가상환경, 모델 캐시, 샘플 이미지는 Git에 올리지 않습니다.

## 프로젝트 구조

```text
.
├─ backend/                         # Spring Boot 백엔드
├─ frontend/                        # Vue 3 프론트엔드
├─ deploy/oci/                      # OCI/Nginx/Redis 운영 구성
├─ PaddleOCR/                       # 사설 OCR + LLM 분석 서비스
├─ docs/                            # 운영 및 기능 문서
├─ docker-compose.yml               # 로컬 통합 실행
├─ docker-compose.oci.app.yml       # OCI 앱 서버 실행
├─ docker-compose.oci.data.yml      # OCI 데이터 서버 실행
├─ .env.example                     # 로컬 실행 환경변수 예시
├─ .env.oci.app.example             # OCI 앱 서버 환경변수 예시
└─ .env.oci.data.example            # OCI 데이터 서버 환경변수 예시
```

## 배포 구조

### 로컬 통합 실행

로컬 개발 환경에서는 하나의 Docker Compose로 데이터베이스, 스토리지, 백엔드, 프론트엔드를 함께 실행합니다.

```powershell
Copy-Item .env.example .env
docker compose up -d --build
```

### OCI 분리 배포

운영 환경은 역할별로 분리된 Compose 구성을 기준으로 합니다.

| 영역 | 주요 구성 | Compose 파일 |
| --- | --- | --- |
| 앱 서버 | backend, frontend | `docker-compose.oci.app.yml` |
| 데이터 서버 | MariaDB, MinIO, minio-init | `docker-compose.oci.data.yml` |
| Redis Cache | 조회/요약 캐시 | `deploy/oci/redis/docker-compose.redis.cache.yml` |
| Redis State | 세션성 상태/락/운영 상태 | `deploy/oci/redis/docker-compose.redis.state.yml` |
| Reverse Proxy | Nginx, TLS, public routing | `deploy/oci/nginx/` |

앱 서버 실행:

```bash
docker compose --env-file .env.oci.app -f docker-compose.oci.app.yml up -d --build backend frontend
```

데이터 서버 실행:

```bash
docker compose --env-file .env.oci.data -f docker-compose.oci.data.yml up -d
```

Redis Cache 실행:

```bash
cd deploy/oci/redis
docker compose --env-file .env.redis.cache -f docker-compose.redis.cache.yml up -d
```

Redis State 실행:

```bash
cd deploy/oci/redis
docker compose --env-file .env.redis.state -f docker-compose.redis.state.yml up -d
```

## Jenkins 배포 구조

Jenkins는 GitHub `main` 브랜치 변경을 기준으로 앱 서버의 `backend`와 `frontend` 컨테이너를 다시 빌드하고 재기동하는 역할을 합니다.

민감정보 보호를 위해 README에는 실제 Jenkins URL, 서버 IP, SSH 키 파일명, 운영 계정명, 도메인, 비밀번호를 기록하지 않습니다. Jenkins Job에서는 Jenkins Credentials 또는 Jenkins 서버 내부 보호 경로의 키를 사용하고, GitHub에는 `.env`, SSH private key, 실제 서버 주소를 커밋하지 않습니다.

### 흐름

1. GitHub `main` 브랜치에 변경 사항을 push합니다.
2. Jenkins Job이 webhook 또는 수동 빌드로 실행됩니다.
3. Jenkins workspace에서 repository를 checkout합니다.
4. Jenkins가 SSH로 앱 서버에 접속합니다.
5. 앱 서버의 배포 디렉터리에서 `git fetch`와 `git reset --hard origin/main`을 수행합니다.
6. `docker-compose.oci.app.yml` 설정을 검증합니다.
7. `backend`, `frontend`만 `up -d --build`로 재빌드 및 재기동합니다.
8. `docker compose ps`로 상태를 확인합니다.

데이터 서버, Redis 서버, MinIO 데이터 볼륨은 일반 앱 배포마다 재생성하지 않습니다.

### Jenkins Execute shell 예시

아래 스크립트는 값이 노출되지 않도록 전부 환경변수 또는 자리표시자로 작성되어 있습니다. 실제 Jenkins Job에서는 Jenkins Credentials Binding 또는 Jenkins 전용 보호 경로를 사용하세요.

```bash
#!/usr/bin/env bash
set -euo pipefail

SSH_KEY="${JENKINS_SSH_KEY_PATH:?JENKINS_SSH_KEY_PATH is required}"
REMOTE="${DEPLOY_USER:?DEPLOY_USER is required}@${DEPLOY_HOST:?DEPLOY_HOST is required}"
APP_DIR="${APP_DIR:-/home/<deploy-user>/calen}"
BRANCH="${BRANCH:-main}"
ENV_FILE="${ENV_FILE:-.env.oci.app}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.oci.app.yml}"

ssh -i "${SSH_KEY}" \
  -o StrictHostKeyChecking=accept-new \
  "${REMOTE}" \
  "APP_DIR='${APP_DIR}' BRANCH='${BRANCH}' ENV_FILE='${ENV_FILE}' COMPOSE_FILE='${COMPOSE_FILE}' bash -se" <<'REMOTE_SCRIPT'
set -euo pipefail

cd "${APP_DIR}"

git fetch origin "${BRANCH}"
git reset --hard "origin/${BRANCH}"

docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" config --quiet
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d --build backend frontend
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" ps
REMOTE_SCRIPT
```

### Jenkins 운영 체크리스트

- SSH private key는 GitHub에 올리지 않습니다.
- Jenkins Job 로그에 비밀번호, 토큰, private key 경로가 찍히지 않도록 `set -x`를 사용하지 않습니다.
- 배포 서버의 `APP_DIR`은 SSH 접속 사용자에게 접근 권한이 있어야 합니다.
- SSH 접속 사용자가 Docker를 실행할 수 있어야 합니다. 일반적으로 `docker` 그룹 권한 또는 제한된 sudo 정책을 사용합니다.
- 앱 배포 Job은 앱 서버 Compose만 갱신합니다. 데이터 서버와 Redis는 별도 운영 절차로 관리합니다.
- Jenkins는 필요할 때만 외부 접근을 열고, 계정/권한/CSRF/crumb/방화벽 설정을 유지합니다.
- 실제 운영 값은 Jenkins Credentials, 서버의 `.env.oci.*`, 보안 저장소에서만 관리합니다.

## 환경변수와 민감정보 정책

- 실제 `.env`, `.env.oci.app`, `.env.oci.data`, Redis env, SSH key, rclone 설정 파일은 Git에 올리지 않습니다.
- 저장소에는 `.example` 파일만 커밋합니다.
- README, docs, issue, commit message에는 실제 IP, 포트포워딩 정보, private key 파일명, 비밀번호, JWT key, MinIO secret, DB password를 적지 않습니다.
- 기본값에 `change-me-*`가 들어간 설정은 운영 전에 반드시 교체합니다.
- 현재 저장소 추적 대상은 예시 파일 기준입니다. 실제 `.env`는 `.gitignore`로 제외되어야 합니다.

## 개발 명령

### Frontend

```powershell
cd frontend
cmd /c npm install
cmd /c npm run build
```

### Backend

```powershell
cd backend
.\gradlew.bat test
```

## 검증 기준

- 프론트엔드 변경 후 `frontend`에서 `cmd /c npm run build`를 실행합니다.
- 백엔드 변경 후 Gradle test 또는 최소 `classes`/`bootJar` 검증을 수행합니다.
- 배포 전 `docker compose config --quiet`로 Compose 설정을 검증합니다.
- Vue SFC는 `<script setup>`과 JavaScript를 사용합니다. TypeScript 파일 또는 `lang="ts"`를 추가하지 않습니다.

## 관련 문서

- [Architecture](./docs/architecture.md)
- [Household Development History](./docs/household_development_history.md)
- [Security Patch History](./docs/security_patch_history.md)
- [Travel Map Development History](./docs/travel_my_map_development_history.md)
- [OCI Project Tenant Provisioning Guide](./docs/OCI_Project_Tenant_Provisioning_Guide.md)
- [Google Drive DB 백업 가이드](./docs/dbtogdrive.md)
- [Google Drive 복구 가이드](./docs/db_restore_from_gdrive.md)

## Wiki

GitHub Wiki에는 운영 중 발견한 가계부, 여행 지도, 보안 패치 관련 상세 이력을 별도 문서로 관리합니다.

- `Household-Development-History`
- `Security-Patch-History`
- `Travel-Map-Development-History`
