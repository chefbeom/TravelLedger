# OCI MinIO Presigned URL 설정 가이드

이 문서는 현재 프로젝트에서 실제로 적용한 `MinIO presigned URL` 전환 과정을 정리한 문서입니다.

목표는 아래와 같습니다.

- 브라우저가 대용량 사진을 `backend`를 거치지 않고 MinIO로 직접 업로드
- `backend`는 presigned URL 발급과 업로드 완료 처리만 담당
- 기존 기능과 화면은 그대로 유지

## 1. 현재 구조

- 메인 서버
  - `backend`
  - `frontend`
  - 호스트 `nginx`
- 서브 서버
  - `MariaDB`
  - `MinIO`

프로젝트에서 presigned 업로드를 켜는 핵심 값은 아래 두 가지입니다.

- 메인 `.env.oci.app`
  - `MINIO_PUBLIC_API=https://minio.calenledger.kro.kr`
  - `TRAVEL_PRESIGNED_UPLOAD_ENABLED=true`
- 메인과 서브가 공통으로 맞아야 하는 값
  - `MINIO_ROOT_USER`
  - `MINIO_ROOT_PASSWORD`
  - `MINIO_CLOUD_BUCKET`

## 2. 도메인 / DNS

presigned URL은 브라우저가 직접 접근해야 하므로, `private IP`가 아니라 외부에서 접근 가능한 `HTTPS` 주소가 필요합니다.

이번 구성에서는 도메인을 하나만 사용합니다.

- `minio.calenledger.kro.kr`

DNS는 `A 레코드`로만 연결합니다.

- `minio.calenledger.kro.kr -> 158.180.66.181`

중요:

- DNS에는 `:9000` 같은 포트를 넣지 않습니다.
- 포트 분기와 HTTPS는 Nginx에서 처리합니다.

## 3. 서브 서버 Nginx 설정

MinIO 자체에 바로 인증서를 붙이지 않고, 서브 서버 Nginx가 `443`에서 SSL 종료를 하고 내부의 `127.0.0.1:9000` MinIO API로 프록시합니다.

프로젝트에 포함된 예시 파일:

- [deploy/oci/nginx/minio.calenledger.kro.kr.conf](C:/Users/kjs99/Desktop/calen/deploy/oci/nginx/minio.calenledger.kro.kr.conf)

역할:

- `80 -> 443` 리다이렉트
- `443 -> 127.0.0.1:9000` 프록시
- presigned PUT 요청용 CORS 응답 처리
- 큰 파일 업로드를 위해 `proxy_request_buffering off`
- 여러 서비스 프론트 도메인을 origin allowlist로 관리

## 4. 서브 서버 방화벽 / OCI 보안 규칙

필요 포트:

- `80/tcp`
- `443/tcp`
- 내부 통신용
  - `3306/tcp`
  - `9000/tcp`

권장:

- `3306`, `9000`은 메인 서버 private IP만 허용
- `80`, `443`은 외부 브라우저 접근 허용

## 5. 메인 서버 환경값

메인 `.env.oci.app` 예시:

```env
DB_INTERNAL_HOST=10.0.0.2
MINIO_API_INTERNAL_URL=http://10.0.0.2:9000
MINIO_PUBLIC_API=https://minio.calenledger.kro.kr
TRAVEL_PRESIGNED_UPLOAD_ENABLED=true
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=실제 MinIO 비밀번호
MINIO_CLOUD_BUCKET=budgetjourneybucket
```

중요:

- `MINIO_API_INTERNAL_URL`은 백엔드 내부 통신용
- `MINIO_PUBLIC_API`는 브라우저가 직접 접근할 공개 HTTPS 주소
- `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`는 서브 MinIO 실제 값과 반드시 같아야 합니다

## 6. 실제 적용 과정에서 확인된 중요 포인트

### 6-1. `MINIO_ROOT_USER` 불일치 시 403 발생

실제 적용 중, 메인 `.env.oci.app`에 아래처럼 잘못 들어간 값 때문에 presigned URL이 `403 Forbidden`이 났습니다.

```env
MINIO_ROOT_USER=calenledger
```

브라우저 요청의 `X-Amz-Credential`에 이 값이 그대로 반영되어 서명 주체가 MinIO 실제 사용자와 달라졌습니다.

해결:

```env
MINIO_ROOT_USER=minioadmin
```

즉, presigned URL은 `MINIO_ROOT_USER`가 다르면 바로 실패합니다.

### 6-2. `MINIO_PUBLIC_API`가 없으면 direct upload가 켜져도 의미가 없음

`TRAVEL_PRESIGNED_UPLOAD_ENABLED=true`만 켜고 `MINIO_PUBLIC_API`가 비어 있거나, 브라우저가 접근할 수 없는 주소를 넣으면 direct upload가 동작하지 않습니다.

잘못된 예:

- `http://10.0.0.2:9000`

이 값은 메인 backend 내부에서는 되지만, 사용자 브라우저에서는 접근할 수 없습니다.

### 6-3. bucket CORS 대신 Nginx CORS 사용

이번 구성에서는 MinIO bucket CORS를 직접 설정하려고 했지만, 실제 적용 중 `mc cors set`이 정상 동작하지 않았습니다.

그래서 현재 프로젝트 문서 기준 권장 방식은:

- bucket CORS 대신
- Nginx reverse proxy에서 `OPTIONS`, `Access-Control-Allow-*`를 처리

입니다.

즉, 현재 프로젝트에 포함된 설정 예시는 **Nginx CORS 기준**입니다.

현재 repo 기준 allowlist 예시는 아래 두 도메인입니다.

- `https://www.innoutdrive.space`
- `https://www.fileinnout.kro.kr`

새 프로젝트를 붙일 때는 MinIO 사용자/버킷을 만드는 것과 별개로, shared MinIO public domain Nginx allowlist에도 새 frontend origin을 추가해야 합니다.

## 7. 점검 순서

### 7-1. 메인 backend 내부 값 확인

```bash
docker exec calen-app-backend-1 printenv MINIO_NAME
docker exec calen-app-backend-1 printenv MINIO_API
docker exec calen-app-backend-1 printenv MINIO_PUBLIC_API
```

정상 예:

- `MINIO_NAME=minioadmin`
- `MINIO_API=http://10.0.0.2:9000`
- `MINIO_PUBLIC_API=https://minio.calenledger.kro.kr`

### 7-2. 서브 Nginx 확인

```bash
nginx -t
curl -I https://minio.calenledger.kro.kr
```

루트 요청은 `400` XML이 나와도 MinIO API가 살아 있으면 정상일 수 있습니다.

### 7-3. 브라우저 업로드 흐름

브라우저 개발자도구 Network에서 아래 순서를 확인합니다.

1. `POST /api/travel/.../media/presign`
2. `PUT https://minio.calenledger.kro.kr/...`
3. `POST /api/travel/.../media/complete`

이 순서면 presigned URL direct upload가 정상입니다.

## 8. 현재 프로젝트 기준 체크 결과

현재 프로젝트 코드와 실제 적용 과정 비교 기준으로, presigned URL에 필요한 주요 항목은 아래와 같습니다.

- 코드 지원
  - 이미 구현되어 있음
  - `frontend/src/lib/api.js`
  - `backend/src/main/java/com/playdata/calen/travel/service/TravelMediaStorageService.java`
- 공개 HTTPS MinIO API
  - 이번 문서와 Nginx 설정 예시로 프로젝트에 반영
- 직접 업로드 플래그
  - `.env.oci.app`에서 제어
- 내부 MinIO 주소
  - `.env.oci.app`에서 제어

현재 문서화 기준에서 누락되면 안 되는 주의점:

- `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` 불일치 금지
- `MINIO_PUBLIC_API`는 반드시 공개 HTTPS 주소
- `private IP`를 presigned URL 공개 주소로 쓰지 않기
- CORS는 현재 Nginx 기준으로 처리

## 9. 요약

현재 프로젝트에서 presigned URL을 쓰려면 아래 4가지만 맞추면 됩니다.

1. `minio.calenledger.kro.kr` 도메인 준비
2. 서브 Nginx에서 SSL + reverse proxy + CORS 적용
3. 메인 `.env.oci.app`에서 `MINIO_PUBLIC_API` 설정
4. 메인 `.env.oci.app`에서 `TRAVEL_PRESIGNED_UPLOAD_ENABLED=true`

이번 문서와 Nginx 설정 파일은 실제로 적용했던 절차를 기준으로 작성되어, 이후 같은 구조를 다시 구성할 때 그대로 참고할 수 있습니다.
