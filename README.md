# Calen

Spring Boot 백엔드와 Vue 3 프런트엔드로 구성된 풀스택 서비스입니다.  
가계부, 여행 예산/로그, 가족 앨범 기능을 한 프로젝트에서 함께 제공합니다.

## 주요 기능

- 가계부
  - 달력형 가계부
  - 통계 요약, 검색, 인사이트, 비교
  - CSV 저장
  - 엑셀 가져오기
- 여행
  - 여행 계획 및 예산안 관리
  - 실제 지출 기록
  - 경로, 사진, GPX 업로드
- 가족 앨범
  - 카테고리별 사진/영상 업로드
  - 앨범 생성 및 공유
- 계정
  - 공개 회원가입 비활성화
  - 로그인한 사용자가 1회용 초대 링크 생성
  - 초대 링크로만 새 계정 가입 가능
  - 같은 IP에서 로그인 5회 실패 시 24시간 차단

## 기술 스택

- `frontend`: Vue 3 + Vite + Nginx
- `backend`: Spring Boot + Spring Security + JPA
- `mariadb`: 메인 관계형 데이터베이스
- `minio`: 백엔드가 사용하는 오브젝트 스토리지

## 업로드 방식

현재 기본 배포 기준으로 브라우저가 MinIO에 직접 업로드하지 않습니다.

- 여행 업로드: 백엔드 경유
- 가족 앨범 업로드: 백엔드 경유
- GPX 업로드: 백엔드 경유

즉, 기본 운영에서는 presigned URL 업로드를 사용하지 않습니다.

## 로컬 Docker Compose 실행

### 1. 환경 파일 생성

```powershell
Copy-Item .env.example .env
```

### 2. `.env` 값 수정

최소한 아래 값은 바꾸는 것을 권장합니다.

- `DB_PASSWORD`
- `DB_ROOT_PASSWORD`
- `MINIO_ROOT_PASSWORD`
- `JWT_KEY`

### 3. 실행

```powershell
docker compose up -d --build
```

### 4. 접속 주소

- 앱: `http://localhost:8080`
- MinIO API: `http://localhost:9000`
- MinIO Console: `http://localhost:9001`

### 5. 시드 계정

`APP_SEED_ENABLED=true` 이면 아래 테스트 계정이 생성됩니다.

- `hana / test1234`
- `minsu / test1234`

## OCI 단일 VM 배포

이 프로젝트는 OCI 가상머신 1대에 다음 구조로 배포할 수 있습니다.

- 외부 공개 진입점: 호스트 Nginx
- 프런트 컨테이너: `127.0.0.1:8081`
- 백엔드 컨테이너: `127.0.0.1:8080`
- MariaDB: `127.0.0.1:3306`
- MinIO Console: `127.0.0.1:9001`
- MinIO API: Docker 내부 네트워크 전용

즉, 브라우저는 Nginx만 보고, 컨테이너끼리는 내부 네트워크로 통신합니다.

### 1. OCI용 환경 파일 준비

```bash
cp .env.example .env
```

운영에서는 아래 항목을 반드시 점검하세요.

```env
OCI_FRONTEND_BIND_HOST=127.0.0.1
OCI_FRONTEND_BIND_PORT=8081
OCI_BACKEND_BIND_HOST=127.0.0.1
OCI_BACKEND_BIND_PORT=8080
OCI_DB_BIND_HOST=127.0.0.1
OCI_DB_BIND_PORT=3306
OCI_MINIO_CONSOLE_BIND_HOST=127.0.0.1
OCI_MINIO_CONSOLE_BIND_PORT=9001

APP_SEED_ENABLED=false
TRAVEL_PRESIGNED_UPLOAD_ENABLED=false
H2_CONSOLE_ENABLED=false
```

비밀번호류 값도 운영용으로 바꿔야 합니다.

### 2. OCI 스택 실행

```bash
docker compose -f docker-compose.oci.yml up -d --build
```

### 3. 상태 확인

```bash
docker compose -f docker-compose.oci.yml ps
docker compose -f docker-compose.oci.yml logs -f backend
docker compose -f docker-compose.oci.yml logs -f frontend
```

## 호스트 Nginx

OCI 운영에서는 호스트 Nginx가 TLS를 종료하고, 아래처럼 프록시하는 구성을 권장합니다.

- `/` -> `127.0.0.1:8081`
- `/api/` -> `127.0.0.1:8080`

저장소에는 예시 설정 파일이 포함되어 있습니다.

- [www.travelledger.kro.kr.conf](/C:/Users/kjs99/Desktop/calen/deploy/oci/nginx/www.travelledger.kro.kr.conf)

실제 운영 시에는 반드시 아래 항목을 본인 도메인 기준으로 수정하세요.

- `server_name`
- 인증서 경로
- HTTP -> HTTPS 리다이렉트 주소

## 내 PC에서 DB 접속하기

MariaDB는 OCI 서버의 로컬호스트에만 바인딩되어 있으므로, 외부에 직접 `3306`을 열 필요가 없습니다.  
대신 SSH 터널을 통해 안전하게 접속하는 방식을 권장합니다.

### 1. 내 PC에서 SSH 터널 생성

```bash
ssh -L 13306:127.0.0.1:3306 ubuntu@<OCI_PUBLIC_IP>
```

### 2. Workbench / VS Code / DBeaver 접속 정보

- Host: `127.0.0.1`
- Port: `13306`
- Database: `calen`
- Username: `.env`의 `DB_USER`
- Password: `.env`의 `DB_PASSWORD`

즉, 로컬 `13306` 포트로 접속하면 SSH가 OCI 서버의 `127.0.0.1:3306`으로 안전하게 전달해줍니다.

## 초대 링크 기반 가입

공개 회원가입은 막혀 있습니다.  
새 계정은 로그인한 기존 사용자가 만든 1회용 초대 링크로만 생성할 수 있습니다.

### 사용 흐름

1. 기존 계정으로 로그인
2. 런처 화면에서 초대 링크 생성
3. 생성된 링크를 새 사용자에게 전달
4. 새 사용자가 `/#invite/<token>` 링크로 접속
5. 로그인 ID, 표시 이름, 비밀번호 입력 후 가입
6. 가입 성공 후 바로 로그인
7. 같은 링크는 다시 사용할 수 없음

## 데이터 볼륨

Docker named volume:

- `mariadb-data`
- `minio-data`
- `backend-uploads`

## 데이터까지 전부 삭제

로컬 기본 Compose:

```powershell
docker compose down -v
```

OCI Compose:

```bash
docker compose -f docker-compose.oci.yml down -v
```
