# 아키텍처

이 문서는 OCI 분리 배포, Redis 도입, 여행 미디어 처리 개선 이후의 현재 Calen 구조를 요약합니다.

## 1. 제품 구조

Calen은 크게 아래 네 도메인으로 구성됩니다.

- 가계부
- 여행
- 가족 앨범
- 계정 / 관리자

각 도메인은 화면과 업무 흐름이 다르지만, 아래 자원을 공유합니다.

- 공통 인증/세션
- 하나의 백엔드 애플리케이션
- 하나의 MariaDB
- 하나의 오브젝트 스토리지 계층

## 2. 배포 구조

### 로컬 개발

로컬에서는 아래를 한 번에 띄울 수 있습니다.

- frontend
- backend
- MariaDB
- MinIO

기준 파일:

- [`docker-compose.yml`](/C:/Users/kjs99/Desktop/calen/docker-compose.yml)

### OCI 운영

운영은 책임별로 나눈 구조를 기준으로 합니다.

#### 앱 서버

- Spring Boot backend
- Vue frontend
- public nginx

관련 파일:

- [`docker-compose.oci.app.yml`](/C:/Users/kjs99/Desktop/calen/docker-compose.oci.app.yml)
- [`.env.oci.app.example`](/C:/Users/kjs99/Desktop/calen/.env.oci.app.example)

#### 데이터 서버

- MariaDB
- MinIO

관련 파일:

- [`docker-compose.oci.data.yml`](/C:/Users/kjs99/Desktop/calen/docker-compose.oci.data.yml)
- [`.env.oci.data.example`](/C:/Users/kjs99/Desktop/calen/.env.oci.data.example)

#### Redis A: 캐시 서버

- 역지오코딩 캐시
- 여행 목록 요약 캐시
- 여행 포트폴리오 캐시
- 내 지도 개요 캐시

#### Redis B: 상태/락 서버

- 로그인 시도 횟수/차단 상태
- 관리자 백업/복구 작업 락

관련 파일:

- [`deploy/oci/redis/docker-compose.redis.cache.yml`](/C:/Users/kjs99/Desktop/calen/deploy/oci/redis/docker-compose.redis.cache.yml)
- [`deploy/oci/redis/docker-compose.redis.state.yml`](/C:/Users/kjs99/Desktop/calen/deploy/oci/redis/docker-compose.redis.state.yml)

## 3. 여행 미디어 흐름

최근 구조 변경 중 가장 큰 축은 여행 이미지 업로드/제공 방식입니다.

### 업로드 흐름

1. 프런트가 EXIF / 날짜 / GPS 메타데이터를 읽음
2. 프런트가 백엔드에 presigned 업로드 대상을 요청
3. 브라우저가 원본 파일을 MinIO로 직접 업로드
4. 백엔드가 업로드 완료를 검증
5. 백엔드가 DB에 메타데이터를 저장
6. 백엔드가 준비된 썸네일을 생성

### 저장 책임

- DB는 미디어 메타데이터를 저장
- MinIO는 원본 이미지를 저장
- MinIO는 준비된 썸네일도 함께 저장

이미지 바이너리는 DB에 저장하지 않습니다.

### 썸네일 전략

준비된 썸네일 폭:

- `320`
- `480`
- `960`

현재 동작:

- 썸네일 요청 시 준비된 썸네일을 먼저 확인
- 준비된 썸네일이 없으면 기존 즉석 생성 fallback 가능
- 원본은 크게 보기 / 다운로드에 사용

## 4. Redis 구조

### Redis A

Redis A는 캐시 전용 역할입니다.

현재 사용처:

- 역지오코딩 캐시
- 여행 목록 요약
- 여행 포트폴리오 요약
- 내 지도 개요 데이터

설계 포인트:

- fail-open
- Redis A 장애가 앱 기동 실패로 이어지지 않음
- 재연결은 backoff 기반

### Redis B

Redis B는 임시 상태와 작업 제어에 사용합니다.

현재 사용처:

- 로그인 시도 상태
- 백업/복구 작업 락

이 분리는 캐시 부하가 락/상태값 처리에 영향을 주지 않게 하려는 목적입니다.

## 5. 여행 지도 구조

여행 지도 관련 기능은 한 번에 모든 상세를 내려주는 방식보다 선택적 로딩 쪽으로 이동했습니다.

### 내 지도

- 개요 데이터 우선 로드
- 선택된 핀 상세는 lazy loading
- 주변 핀은 제한된 범위로 함께 프리패치

### 여행 보기 / 여행 로그 지도

- 팝업 이미지 로딩은 더 선택적으로 처리
- 선택 핀 변경 시 불필요한 전체 재렌더를 줄이는 방향으로 조정

## 6. 가계부 달력 구조

가계부 달력은 사용자 크기 조절 기능을 포함합니다.

최근 개선 포인트:

- 리사이즈 중 셀 잘림 감소
- 수직/수평 조절 시 비율 깨짐 완화
- 월 달력 전체가 함께 줄어드는 방향으로 보정

## 7. 운영 포인트

### MinIO

- 주요 오브젝트 스토리지
- 여행 미디어 presigned 업로드 지원
- public 업로드 접근은 백엔드와 reverse proxy 설정을 통해 제어

### 백업

- DB / MinIO 백업과 복구는 관리자 기능에서 처리
- 중복 실행 방지는 Redis B 락 사용

### 캐시 무효화

여행 관련 쓰기 작업 이후 아래 캐시는 무효화됩니다.

- 여행 목록 요약
- 포트폴리오 요약
- 내 지도 개요

## 8. 다음에 읽을 문서

운영과 배포 기준으로는 아래 문서를 이어서 보는 것을 권장합니다.

1. [OCI DB + MinIO 분리 배포 가이드](/C:/Users/kjs99/Desktop/calen/docs/OCI_DB_MinIO_분리_배포가이드.md)
2. [OCI MinIO presigned URL 설정 가이드](/C:/Users/kjs99/Desktop/calen/docs/OCI_MinIO_presignedURL_설정가이드.md)
3. [OCI Redis 2서버 설정 가이드](/C:/Users/kjs99/Desktop/calen/docs/OCI_Redis_2Server_설정가이드.md)
