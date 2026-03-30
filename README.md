# 랫저 (Calen)

생활 기록을 한 곳에서 다루기 위해 만든 풀스택 웹 애플리케이션입니다.  
가계부, 여행 기록, 여행 경로, 여행 사진, 가족 앨범, 관리자 기능을 하나의 서비스 안에서 연결해 사용하는 구조로 설계했습니다.

포트폴리오 관점에서는 아래 두 가지를 핵심으로 잡았습니다.

- 기록이 쌓일수록 관리가 쉬워지는 사용자 경험
- 실제 운영을 고려한 인증, 배포, 백업, 관리자 기능

## 1. 프로젝트 개요

- **프로젝트명**: 랫저
- **형태**: Spring Boot + Vue 3 기반 풀스택 웹앱
- **주요 도메인**:
  - 달력 가계부
  - 여행 예산 / 여행 로그 / 여행 경로 / 여행 사진
  - 가족 앨범
  - 관리자 페이지 / 문의 메일함
- **배포 환경**:
  - Oracle Cloud Infrastructure(OCI)
  - Docker Compose
  - Nginx Reverse Proxy
  - HTTPS

## 2. 핵심 기능

### 2-1. 가계부

- 달력 기반 가계부
- 빠른 거래 입력
- 거래 등록 / 수정 직후 실행 취소(undo)
- 과거 내역 검색
- 검색 결과 수정 / 삭제
- 삭제 시 휴지통 이동 및 복구
- 통계 / 인사이트 / 기간 비교
- 전체 기간 검색 및 통계 범위 지원
- CSV 내보내기
  - 현재 로그인 세션에서 검증된 2차 비밀번호 기준 보호 ZIP 생성
- 엑셀/CSV 가져오기
- 반복 입력 자동완성
- 사용자 설정 집계 카드

### 2-2. 여행

- 여행 계획 및 예산 관리
- 여행 기록 작성
  - 장소, 메모, 사진, 시간, 위치 기반 기록
- 지도 기반 여행 로그 확인
- 여행 기록 핀을 기반으로 이동 경로 생성
- 기록 핀 사이에 경로 보정 핀 추가
- 저장된 경로 수정
- 여행 사진 갤러리
- 공유 전시
  - 완료된 여행을 다른 사용자에게 읽기 전용으로 공유

### 2-3. 가족 앨범

- 가족 구성원별 기록 관리
- 카테고리 기반 앨범 구성
- 사진 / 영상 업로드 및 열람

### 2-4. 계정 / 보안 / 관리자

- 공개 회원가입 비활성화
- 1회용 초대 링크 기반 가입
- 비밀번호 + 8자리 2차 비밀번호 로그인
- 로그인 실패 5회 시 IP 24시간 차단
- 관리자 페이지 제공
  - 로그인 기록
  - 차단 IP 목록
  - 사용자 상태 관리
  - 최근 초대 링크
  - 문의 메일함 / 보관함 / 답변
- 사용자 프로필 페이지
  - 내 정보 확인
  - 문의 작성
  - 관리자 답변 확인

## 3. 기술 스택

### Frontend

- Vue 3
- Vite
- Leaflet

### Backend

- Java 17
- Spring Boot 3
- Spring MVC
- Spring Security
- Spring Data JPA
- Bean Validation

### Database / Storage

- MariaDB
- H2(개발용)
- MinIO

### Infra / Ops

- Docker / Docker Compose
- Nginx
- OCI
- Google Drive 백업(rclone)

## 4. 구현 포인트

### 4-1. 사용자 경험

- 달력과 빠른 입력을 중심으로 한 가계부 입력 흐름
- 검색 결과에서 바로 수정 / 삭제 / 복구로 이어지는 관리 흐름
- 반복 입력 자동완성으로 자주 쓰는 거래를 빠르게 재입력
- 여행 기록, 여행 경로, 여행 사진을 분리하되 서로 연결된 구조

### 4-2. 보안

- 회원가입 차단 + 초대 링크 기반 계정 생성
- 2차 비밀번호 추가 검증
- 로그인 실패 IP 차단
- 관리자 기능 접근 제어
- CSV 내보내기 보호

### 4-3. 운영

- 프런트 / 백엔드 / DB / 오브젝트 스토리지를 컨테이너 분리
- Nginx Reverse Proxy + HTTPS
- OCI 서버 운영 가이드 정리
- Google Drive 자동 백업 및 복구 가이드 정리

## 5. 화면 구성

메인 런처 기준 기능 구분은 아래와 같습니다.

1. 가계부
2. 여행 예산
3. 여행 로그
4. 여행 사진
5. 가족 앨범
6. 관리자 페이지(관리자만)
7. 내 프로필

## 6. 실행 방법

### 6-1. 로컬 실행

루트 폴더에서 환경 파일을 준비합니다.

```powershell
Copy-Item .env.example .env
```

개발 환경 실행:

```powershell
docker compose up -d --build
```

### 6-2. OCI 배포

운영 서버에서는 OCI 전용 Compose 파일을 사용합니다.

```bash
docker compose -f docker-compose.oci.yml up -d --build
```

핵심 운영 전략:

- 프런트 / 백엔드 / MinIO 콘솔은 `127.0.0.1` 바인딩
- 외부 공개는 Nginx가 담당
- HTTPS 종단은 호스트 Nginx에서 처리

## 7. DB 접속

운영 DB는 외부에 직접 공개하지 않고 SSH 터널로 접속합니다.

예시:

```bash
ssh -L 13306:127.0.0.1:3306 ubuntu@<SERVER_IP>
```

그 후 DB 툴에서 아래처럼 연결합니다.

- Host: `127.0.0.1`
- Port: `13306`
- Database: `.env`의 `DB_NAME`
- Username: `.env`의 `DB_USER`
- Password: `.env`의 `DB_PASSWORD`

## 8. 백업 / 복구 문서

운영 문서는 별도 파일로 정리해 두었습니다.

- [OCI Docker Nginx HTTPS 설정 가이드](docs/OCI_도커_Nginx_HTTPS_설정가이드.md)
- [Google Drive DB 백업 가이드](docs/dbtogdrive.md)
- [Google Drive 백업 복구 가이드](docs/db_restore_from_gdrive.md)
- [아키텍처 메모](docs/architecture.md)

## 9. 테스트 / 검증

### Backend

```powershell
.\gradlew.bat test
```

### Frontend

```powershell
cmd /c npm install
cmd /c npm run build
```

## 10. 포트폴리오 관점에서 강조할 수 있는 점

- 단순 CRUD를 넘어서, 실제 생활 기록 흐름에 맞춘 가계부 UX를 설계했습니다.
- 여행 기록을 지도, 경로, 사진, 공유 전시까지 확장해 하나의 여행 데이터 흐름으로 묶었습니다.
- 초대 기반 가입, 2차 비밀번호, 관리자 문의 메일함, 로그인 기록, 차단 IP 관리 등 운영 기능을 함께 구현했습니다.
- Docker, Nginx, OCI, Google Drive 백업까지 포함해 “개발 후 운영”까지 직접 다루는 프로젝트로 확장했습니다.

## 11. 참고

- 현재 루트 README는 포트폴리오 소개용 문서입니다.
- 프런트 기본 템플릿 문서는 [frontend/README.md](frontend/README.md)에 별도로 남아 있습니다.
