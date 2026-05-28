# TravelLedger (Calen)

TravelLedger는 가계부, 여행 기록, 여행 사진 지도, 파일 드라이브를 한 서비스 안에서 관리하는 개인 생활 기록 플랫폼입니다. 현재 기준 문서 갱신일은 2026-05-28입니다.

## 현재 구성

### 주요 기능

| 영역 | 현재 기능 |
| --- | --- |
| 메인 대시보드 | 가계부, 여행, 드라이브 요약을 카드/팔레트 형태로 표시합니다. 사용자별 배치와 표시 항목을 저장하고 다크/라이트 모드를 지원합니다. |
| 가계부 | 월 달력 기반 입력, 빠른 거래 입력, 검색, 통계, 비교, 인사이트, 분류 관리, 엑셀 가져오기/내보내기, 변경 이력 복구를 제공합니다. |
| 가계부 OCR | 영수증 또는 거래내역 캡처 이미지를 업로드해 OCR/AI 분석 결과를 검토한 뒤 기존 거래 입력 폼에 적용할 수 있습니다. 자동 저장은 하지 않습니다. |
| 여행 | 여행 계획, 예산/지출, 기억, 미디어, 경로, 사진 지도를 관리합니다. 사진 지도는 클러스터와 뷰포트 렌더링을 사용합니다. |
| 여행 공유 | 공개 여행 지도와 제한 공유 그룹을 제공합니다. 공개/그룹 공유는 기존 개인 여행 기능과 분리되어 동작합니다. |
| 내 사진 | 업로드한 여행 사진을 썸네일 기반 앨범으로 보고, 클릭 시 원본 비율 상세 모달과 위치 정보를 확인합니다. |
| CalenDrive | 구글 드라이브처럼 파일/폴더 탐색, 업로드, 다운로드, 공유, 받은 파일 저장, 휴지통, 썸네일, 프로필 이미지를 관리합니다. |
| 관리자 | 초대 링크, 운영 패널, 백업/복구 관련 진입점을 관리자 영역으로 분리했습니다. |

## 기술 스택

### Frontend

- Vue 3 + JavaScript
- Vite
- Pinia
- GridStack
- Leaflet
- exifr
- CSS scoped style

TypeScript는 사용하지 않습니다. 신규 Vue SFC도 `<script setup>` JavaScript 기준으로 작성합니다.

### Backend

- Java 17
- Spring Boot 3.5
- Spring Security
- Spring Data JPA
- MariaDB
- Redis/Lettuce
- MinIO
- Apache POI
- zip4j

### OCR / AI

- 별도 Windows 1060 PC에서 OCR/AI 분석 서버 운영
- FastAPI 기반 OCR 서버
- PaddleOCR
- Ollama Gemma 계열 모델 또는 n8n 워크플로 연동
- 브라우저는 OCR PC를 직접 호출하지 않고 Backend의 `/api/ledger/ocr/analyze`를 통해 프록시 호출합니다.

### Infra

- Docker / Docker Compose
- OCI 앱 서버와 데이터 서버 분리
- Nginx HTTPS reverse proxy
- Jenkins 기반 GitHub push 배포
- rclone 기반 Google Drive 백업

## 프로젝트 구조

```text
backend/
  src/main/java/...      Spring Boot API, 도메인, 보안, 저장소
frontend/
  src/                   Vue 3 프론트엔드
PaddleOCR/
  ocr_service.py         OCR/AI 분석 FastAPI 서비스
  requirements.txt       OCR 서버 의존성
  install_windows_ocr.ps1
deploy/
  oci/scripts/           OCI 운영/백업/프로비저닝 스크립트
docs/
  *.md                   운영, 배포, 백업, 개발 이력 문서
docker-compose*.yml      로컬/OCI Compose 구성
```

## 로컬 개발

### Frontend

```bash
cd frontend
npm install
npm run dev
npm run build
```

### Backend

```bash
cd backend
./gradlew test
./gradlew bootWar
```

Windows PowerShell에서는 다음처럼 실행할 수 있습니다.

```powershell
cd backend
.\gradlew.bat test
.\gradlew.bat bootWar
```

### Docker Compose

```bash
docker compose up -d --build
```

운영용 Compose는 `docker-compose.oci.app.yml`, `docker-compose.oci.data.yml`을 기준으로 분리되어 있습니다.

## 운영 구조

### 앱 서버

앱 서버는 Backend와 Frontend 컨테이너를 실행합니다. Jenkins 배포는 GitHub main 브랜치 push 이후 앱 서버에서 최신 커밋을 가져와 Backend/Frontend를 다시 빌드하고 재기동하는 방식입니다.

흐름:

```text
GitHub push
  -> Jenkins checkout
  -> SSH to app server
  -> git fetch/reset
  -> docker compose config
  -> docker compose up -d --build backend frontend
```

### 데이터 서버

데이터 서버는 MariaDB, MinIO, Redis 같은 상태 저장 서비스를 담당합니다. 앱 서버와 데이터 서버는 운영 환경변수로 연결합니다.

### Redis

Redis는 캐시/상태 용도로 사용합니다. 장애가 서비스 전체 장애로 번지지 않도록 애플리케이션 레벨에서 가능한 범위의 graceful degradation을 유지합니다. 운영에서는 단일 Redis 또는 캐시/상태 분리 Redis 중 현재 인프라 기준에 맞춰 환경변수로 연결합니다.

### 백업

`deploy/oci/scripts/backup-to-gdrive.sh`는 DB/MinIO 백업을 생성하고 Google Drive 업로드 후 로컬 임시 백업 파일을 정리하는 방향으로 운영합니다. 서버 디스크가 백업 파일 누적으로 가득 차지 않도록 백업 산출물 정리를 반드시 확인합니다.

## OCR 운영

가계부 OCR은 기본 가계부 기능과 분리되어 있습니다. OCR 서버가 꺼져 있어도 일반 가계부 입력, 수정, 삭제, 검색, 통계, 엑셀 가져오기 기능은 계속 사용할 수 있어야 합니다.

Backend 설정 예시:

```env
LEDGER_OCR_ENABLED=true
LEDGER_OCR_BASE_URL=http://<ocr-private-host>:8765
LEDGER_OCR_WORKFLOW_URL=http://<n8n-private-host>:5678/webhook/<workflow>
LEDGER_OCR_API_KEY=<set-in-server-env>
LEDGER_OCR_CONNECT_TIMEOUT=5s
LEDGER_OCR_READ_TIMEOUT=180s
LEDGER_OCR_MAX_FILE_SIZE=10485760
```

민감값은 Git에 올리지 않습니다. 실제 URL, API Key, SSH Key, DB 비밀번호, MinIO Key, OCR 테스트 영수증 이미지는 환경변수 또는 서버 로컬 파일로만 관리합니다.

OCR 흐름:

```text
사용자 이미지 업로드
  -> Backend 인증 확인
  -> OCR/AI 서버 또는 n8n 워크플로 호출
  -> OCR 텍스트와 정형 분석 결과 반환
  -> Frontend 미리보기/수정
  -> 사용자가 거래 등록 버튼으로 최종 저장
```

지원 문서 유형:

- `RECEIPT`: 영수증, 한 장에서 한 거래 제안
- `PAYMENT_CAPTURE`: 거래내역 캡처, 한 장에서 여러 거래 후보
- `AUTO`: 서버가 가능한 범위에서 자동 판단

## 가계부 변경 이력

가계부 검색/수정/일괄 변경은 변경 이력에 기록됩니다. 이력은 전체 스냅샷 대신 변경된 필드 중심의 패치 형태로 저장해 저장 공간 증가를 줄입니다. 복구 시에는 현재 시점도 이력으로 남긴 뒤 선택한 이력 기준으로 되돌릴 수 있습니다.

## 보안 주의

다음 파일과 값은 커밋하지 않습니다.

- `.env`, `.env.*`
- SSH private key
- DB/Redis/MinIO/OCR API Key
- 실제 영수증, 카드 내역, 개인 사진 원본 테스트 파일
- OCR 가상환경과 모델 캐시
- 운영 로그와 백업 산출물

관련 제외 대상은 `.gitignore`와 각 서비스별 운영 문서를 함께 확인합니다.

## 참고 문서

- [Architecture](docs/architecture.md)
- [Windows 1060 OCR Tailscale Setup Guide](docs/Windows_1060_OCR_Tailscale_Setup_Guide.md)
- [DB Restore From Google Drive](docs/db_restore_from_gdrive.md)
- [DB To Google Drive Backup](docs/dbtogdrive.md)
- [OCI Project Tenant Provisioning Guide](docs/OCI_Project_Tenant_Provisioning_Guide.md)
- [Household Development History](docs/household_development_history.md)
- [Travel Map Development History](docs/travel_my_map_development_history.md)
- [Security Patch History](docs/security_patch_history.md)
