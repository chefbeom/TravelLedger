# Calen

Calen은 가계부, 여행 기록, 여행 사진/경로, 가족 앨범, 관리자 운영 기능을 한 서비스 안에서 다루는 생활 기록 플랫폼입니다.

현재 저장소는 다음 흐름을 중심으로 운영됩니다.

- 가계부 입력, 조회, 비교, CSV 내보내기
- 여행 계획, 기록, 사진, GPS, 경로, GPX 연동
- 여행 지도 기반 사진 클러스터링과 대표 사진 관리
- 가족 앨범 카테고리/앨범/사진 관리
- 관리자용 백업, 복구, 운영 제어

## 주요 도메인

### 가계부

- 달력 기반 거래 입력/조회
- 빠른 입력과 반복 입력 보조
- 검색, 필터, 통계, 비교 화면
- CSV 내보내기

### 여행

- 여행 계획/기록/예산 관리
- GPS, 날짜, 시간, 사진 메타데이터 기반 기록 정리
- 지도 기반 경로 표시와 GPX 관리
- 사진 지도, 클러스터, 핀 보기, 대표 사진 지정
- 큰 사진 보기, 원본 보기, 다운로드

### 가족 앨범

- 카테고리 기반 사진/영상 관리
- 페이지 단위 로딩
- 앨범별 정리와 탐색

### 계정 / 관리자

- 로그인, 권한, 재시도 제한
- 백업/복구 작업 제어
- 운영용 상태 관리

## 기술 스택

### 프론트엔드

- Vue 3
- Vite
- Leaflet

### 백엔드

- Java 17
- Spring Boot 3
- Spring MVC
- Spring Security
- Spring Data JPA

### 데이터 / 스토리지

- MariaDB
- MinIO
- Redis Cache
- Redis State

### 인프라

- Docker / Docker Compose
- Nginx
- OCI
- rclone 기반 Google Drive 백업

## 현재 배포 구조

### 로컬 통합 실행

- `docker-compose.yml`

### OCI 분리 배포

- 앱 서버
  - `backend`
  - `frontend`
  - public `nginx`
- 데이터 서버
  - `MariaDB`
  - `MinIO`
- 캐시 서버
  - `Redis Cache`
- 상태 서버
  - `Redis State`

관련 파일:

- [`docker-compose.yml`](./docker-compose.yml)
- [`docker-compose.oci.app.yml`](./docker-compose.oci.app.yml)
- [`docker-compose.oci.data.yml`](./docker-compose.oci.data.yml)
- [`deploy/oci/redis/docker-compose.redis.cache.yml`](./deploy/oci/redis/docker-compose.redis.cache.yml)
- [`deploy/oci/redis/docker-compose.redis.state.yml`](./deploy/oci/redis/docker-compose.redis.state.yml)

## 여행 미디어 처리 현재 기준

### 업로드 구조

여행 사진 업로드는 현재 다음 구조를 기준으로 합니다.

1. 프론트가 원본 이미지에서 필요한 썸네일을 준비합니다.
2. 프론트가 백엔드에 presigned 업로드 대상을 요청합니다.
3. 브라우저가 원본/썸네일을 MinIO로 직접 업로드합니다.
4. 백엔드는 업로드 완료 검증과 메타데이터 저장만 담당합니다.

즉, 여행 업로드 경로에서는 백엔드가 원본 이미지 바이너리를 직접 받지 않는 구조를 목표 상태로 사용합니다.

### 썸네일 정책

여행 사진은 용도별 prepared thumbnail을 사용합니다.

- `pin`
- `mini`
- `preview`
- `detail`

정책은 다음과 같습니다.

- 원본 사진은 크게 보기 또는 다운로드에서만 사용
- 지도, 목록, 선택 카드, 앨범 미리보기는 prepared thumbnail 우선 사용
- 준비되지 않은 예전 사진은 백필 또는 안전한 fallback 경로로 보정
- 원본 이미지를 일반 미리보기 응답으로 다시 흘리지 않음

### 백필과 안전망

- 예전 사진은 배치 백필이 prepared thumbnail을 채웁니다.
- 신규 업로드는 업로드 시점에 바로 prepared thumbnail 체계를 맞춥니다.
- 요청 시점 썸네일 생성은 가능한 한 줄이고, 남더라도 1회성 보정 경로로만 유지합니다.

## 여행 지도 현재 기준

### 지도 모드

- 클러스터 보기
- 핀 보기

### 현재 동작 원칙

- 초기 진입 시 자동 선택 없음
- 클러스터/핀은 한 번 눌렀을 때 선택 정보 갱신
- 팝업에서 큰 사진 바로 열기 가능
- fullscreen에서는 오른쪽 inspector 안에서 클러스터 관련 조작 가능

### 큰 사진 보기

- 지도에서 연 큰 사진은 전체 지도 사진 기준 시간순 이전/다음 이동
- 클러스터 내부 이미지 블록에서 연 큰 사진은 해당 클러스터 내부 사진끼리만 이전/다음 이동
- 대표 사진 지정은 클러스터 내부에서 연 큰 사진 모달에서만 수행

## 최근 안정화 포인트

### 메모리 / OOM

- 원본 이미지를 통째로 힙에 올리던 경로를 제거
- 미리보기는 prepared thumbnail 중심으로 전환
- 스트리밍과 응답 처리 보강으로 OOM 가능성 축소

### 쿼리 / API

- 반복되는 동일 미디어 메타데이터 조회 캐시 적용
- 잘못 조합되던 `/api/api/...` 경로 수정
- 클러스터 상세 재조회 복구 경로 추가

### 프론트 성능

- 지도 렌더에 필요한 데이터만 전달
- 중복 미디어 배열 보관 최소화
- 클러스터 사진 목록 lazy loading / paging 적용

### fullscreen UX

- lightbox를 fullscreen subtree 안에서 렌더
- inspector는 overlay 기준 높이로 제한
- 패널은 범위를 넘지 않고 내부 스크롤만 사용

## 개발 / 실행

### 환경 파일 준비

```powershell
Copy-Item .env.example .env
```

### 로컬 실행

```powershell
docker compose up -d --build
```

### OCI 앱 서버 실행

```bash
docker compose --env-file .env.oci.app -f docker-compose.oci.app.yml up -d --build
```

### OCI 데이터 서버 실행

```bash
docker compose --env-file .env.oci.data -f docker-compose.oci.data.yml up -d
```

### Redis Cache 실행

```bash
cd ~/calen/deploy/oci/redis
docker compose --env-file .env.redis.cache -f docker-compose.redis.cache.yml up -d
```

### Redis State 실행

```bash
cd ~/calen/deploy/oci/redis
docker compose --env-file .env.redis.state -f docker-compose.redis.state.yml up -d
```

## 테스트

### 백엔드

```powershell
.\gradlew.bat test
```

### 프론트엔드

```powershell
cmd /c npm install
cmd /c npm run build
```

## 문서

- [Architecture](./docs/architecture.md)
- [Household Development History](./docs/household_development_history.md)
- [Security Patch History](./docs/security_patch_history.md)
- [Travel Map Development History](./docs/travel_my_map_development_history.md)
- [OCI DB + MinIO 분리 배포 가이드](./docs/OCI_DB_MinIO_분리_배포가이드.md)
- [OCI MinIO presigned URL 설정 가이드](./docs/OCI_MinIO_presignedURL_설정가이드.md)
- [OCI Redis 2Server 설정 가이드](./docs/OCI_Redis_2Server_설정가이드.md)
- [OCI Docker Nginx HTTPS 설정 가이드](./docs/OCI_도커_Nginx_HTTPS_설정가이드.md)
- [Google Drive DB 백업 가이드](./docs/dbtogdrive.md)
- [Google Drive 복구 가이드](./docs/db_restore_from_gdrive.md)

## Wiki

GitHub Wiki에는 운영 중 발생한 가계부/여행 지도/보안 패치 관련 문제와 최종 해결 기준을 별도 문서로 관리합니다.

- Wiki page: `Household-Development-History`
- Wiki page: `Security-Patch-History`
- Wiki page: `Travel-Map-Development-History`
