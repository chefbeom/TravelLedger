# Calen

Calen은 가계부, 여행 기록, 여행 사진/경로, 가족 앨범, 관리자 운영 기능을 하나로 묶은 생활 기록 서비스입니다.

단순한 CRUD 화면보다 실제 사용 흐름을 중심으로 설계했습니다.

- 달력 기반 가계부 입력과 조회
- 여행 계획, 기록, 경로, GPX, 사진을 한 흐름으로 연결
- MinIO presigned URL 기반 직접 업로드
- 가족 앨범 공유
- 관리자 백업/복구와 운영 도구

## 주요 도메인

### 가계부

- 달력 기반 거래 관리
- 빠른 입력과 반복 입력 제안
- 검색, 필터, 통계, 비교 화면
- CSV 내보내기
- 가져오기와 정리 보조 기능

### 여행

- 여행 계획 및 예산 관리
- 날짜, 시간, GPS, 사진 메타데이터 기반 여행 기록
- 핀 기반 경로 생성과 GPX 파일 관리
- 공유 전시
- 여행 보기, 여행 로그, 내 지도를 포함한 지도 경험

### 가족 앨범

- 카테고리 기반 사진/영상 관리
- 가족 앨범 구성과 열람

### 계정 / 관리자

- 로그인, 프로필, 문의
- 로그인 시도 제한
- DB / MinIO 백업 및 복구

## 현재 기술 스택

### 프런트엔드

- Vue 3
- Vite
- Leaflet

### 백엔드

- Java 17
- Spring Boot 3
- Spring MVC
- Spring Security
- Spring Data JPA

### 데이터 / 저장소

- MariaDB
- MinIO
- Redis A: 캐시 서버
- Redis B: 상태/락 서버

### 인프라

- Docker / Docker Compose
- Nginx
- OCI
- rclone 기반 Google Drive 백업 지원

## 현재 배포 구조

프로젝트는 로컬 통합 실행과 OCI 분리 배포를 모두 지원합니다.

### 로컬

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
  - `Redis A`
- 상태/락 서버
  - `Redis B`

관련 파일:

- [`docker-compose.oci.app.yml`](/C:/Users/kjs99/Desktop/calen/docker-compose.oci.app.yml)
- [`docker-compose.oci.data.yml`](/C:/Users/kjs99/Desktop/calen/docker-compose.oci.data.yml)
- [`deploy/oci/redis/docker-compose.redis.cache.yml`](/C:/Users/kjs99/Desktop/calen/deploy/oci/redis/docker-compose.redis.cache.yml)
- [`deploy/oci/redis/docker-compose.redis.state.yml`](/C:/Users/kjs99/Desktop/calen/deploy/oci/redis/docker-compose.redis.state.yml)

## 최근 반영된 핵심 업데이트

### 여행 이미지 업로드

- 여행 사진 업로드가 `presign -> MinIO 직접 PUT -> complete` 흐름을 지원합니다.
- 여러 사진 받기 준비/업로드 과정에 진행 표시가 있습니다.
- 사진 준비/업로드 중 페이지 이탈 시 경고가 표시됩니다.

### 여행 이미지 제공 방식

- 여행 이미지 업로드 직후 준비된 썸네일을 함께 생성합니다.
- 준비된 썸네일 폭:
  - `320`
  - `480`
  - `960`
- 원본은 MinIO에 그대로 저장합니다.
- 준비된 썸네일도 MinIO에 함께 저장합니다.
- 썸네일 요청은 준비된 썸네일을 우선 사용하고, 없으면 기존 즉석 생성으로 fallback 합니다.

### Redis 사용처

- Redis A
  - 역지오코딩 캐시
  - 여행 목록 요약 캐시
  - 여행 포트폴리오 캐시
  - 내 지도 개요 캐시
- Redis B
  - 로그인 시도 상태
  - 백업/복구 작업 락

Redis A는 fail-open 기준으로 동작합니다.

- Redis A가 죽어도 앱은 기동됩니다.
- 캐시만 비활성 fallback 됩니다.
- 재연결은 backoff 기반으로 제한적으로 시도합니다.

### 여행 지도 UX

- 내 지도 상세는 선택 핀 기준 lazy loading 구조입니다.
- 여행 보기 지도 핀 이미지는 더 선택적으로 로딩됩니다.
- 여행 로그/여행 보기 핀 팝업은 불필요한 재렌더를 줄이는 방향으로 조정됐습니다.

### 가계부 달력 UX

- 달력 크기 조절 동작을 개선했습니다.
- 수직/수평 리사이즈 시 잘림과 비율 깨짐을 줄이는 쪽으로 보정했습니다.

## 여행 미디어 처리 요약

현재 여행 이미지 처리는 아래 흐름으로 동작합니다.

1. 프런트가 EXIF / 날짜 / GPS 메타데이터를 읽음
2. 프런트가 백엔드에 presigned 업로드 대상을 요청
3. 브라우저가 원본 파일을 MinIO로 직접 업로드
4. 백엔드가 업로드 완료를 검증
5. 백엔드가 DB에 미디어 메타데이터를 저장
6. 백엔드가 여행 이미지용 준비된 썸네일을 생성
7. 프런트는 미리보기에는 썸네일을, 크게 보기에는 원본을 사용

## 로컬 실행

환경 파일 준비:

```powershell
Copy-Item .env.example .env
```

실행:

```powershell
docker compose up -d --build
```

## OCI 실행

앱 서버:

```bash
docker compose --env-file .env.oci.app -f docker-compose.oci.app.yml up -d --build
```

데이터 서버:

```bash
docker compose --env-file .env.oci.data -f docker-compose.oci.data.yml up -d
```

Redis A:

```bash
cd ~/calen/deploy/oci/redis
docker compose --env-file .env.redis.cache -f docker-compose.redis.cache.yml up -d
```

Redis B:

```bash
cd ~/calen/deploy/oci/redis
docker compose --env-file .env.redis.state -f docker-compose.redis.state.yml up -d
```

## 테스트

백엔드:

```powershell
.\gradlew.bat test
```

프런트엔드:

```powershell
cmd /c npm install
cmd /c npm run build
```

## 문서

- [아키텍처](/C:/Users/kjs99/Desktop/calen/docs/architecture.md)
- [OCI DB + MinIO 분리 배포 가이드](/C:/Users/kjs99/Desktop/calen/docs/OCI_DB_MinIO_분리_배포가이드.md)
- [OCI MinIO presigned URL 설정 가이드](/C:/Users/kjs99/Desktop/calen/docs/OCI_MinIO_presignedURL_설정가이드.md)
- [OCI Redis 2서버 설정 가이드](/C:/Users/kjs99/Desktop/calen/docs/OCI_Redis_2Server_설정가이드.md)
- [OCI Docker Nginx HTTPS 설정 가이드](/C:/Users/kjs99/Desktop/calen/docs/OCI_도커_Nginx_HTTPS_설정가이드.md)
- [Google Drive DB 백업 가이드](/C:/Users/kjs99/Desktop/calen/docs/dbtogdrive.md)
- [Google Drive 복구 가이드](/C:/Users/kjs99/Desktop/calen/docs/db_restore_from_gdrive.md)
