# OCI + Docker + Nginx + HTTPS 설정 가이드

이 문서는 실제로 적용해서 동작을 확인한 배포 설정만 추려 정리한 문서입니다.  
핵심 목적은 다음과 같습니다.

- Ubuntu 24.04 기반 OCI VM에 프로젝트 배포
- Docker Compose로 풀스택 실행
- 호스트 Nginx가 `80/443`만 외부에 노출하고 내부 컨테이너로 reverse proxy
- `backend`, `db`, `minio`는 외부 직접 비공개 유지
- 수동 인증서 또는 발급된 인증서로 HTTPS 적용
- OCI 보안 규칙과 서버 OS 방화벽까지 함께 정리

이 가이드는 특정 서비스 기능 설명이 아니라, 다른 프로젝트에도 재사용할 수 있는 **서버 배포/설정 템플릿**을 목표로 합니다.

---

## 1. 권장 배포 구조

실제로 안정적으로 운영된 구조는 아래와 같습니다.

```text
브라우저
  -> https://www.example.com
  -> 호스트 Nginx (80/443)
  -> frontend 컨테이너 (127.0.0.1:8081)
  -> /api 는 backend 컨테이너 (127.0.0.1:8080)

backend 컨테이너
  -> mariadb (127.0.0.1:3306 또는 Docker 내부망)
  -> minio (Docker 내부망)
```

핵심 원칙:

- 외부 공개 포트는 `22`, `80`, `443` 정도만 유지
- `backend`, `db`, `minio`는 브라우저가 직접 보지 않게 구성
- Docker 컨테이너 포트는 가능하면 `127.0.0.1`에만 바인딩
- 외부 공개는 호스트 Nginx 한 곳에서만 처리

---

## 2. OCI VM 준비

기준 환경:

- OCI Compute Instance
- Ubuntu 24.04
- 공인 IP 연결 완료

### 2-1. 필수 패키지 설치

Ubuntu 24.04에서는 `docker-compose-plugin` 대신 `docker-compose-v2` 패키지를 사용하는 편이 수월했습니다.

```bash
sudo apt update
sudo apt install -y git docker.io docker-compose-v2 nginx
```

자동 시작 설정:

```bash
sudo systemctl enable --now docker nginx
```

일반 사용자로 Docker를 돌릴 계획이면:

```bash
sudo usermod -aG docker $USER
```

이후 재로그인합니다.

---

## 3. 프로젝트 배포 기본 흐름

### 3-1. 저장소 clone

```bash
git clone <YOUR_REPOSITORY_URL> app
cd app
```

### 3-2. 환경 파일 생성

```bash
cp .env.example .env
```

### 3-3. 운영용 `.env` 기본 원칙

운영에서는 호스트 Nginx만 외부에 노출되도록 잡는 것이 안전합니다.

```env
OCI_FRONTEND_BIND_HOST=127.0.0.1
OCI_FRONTEND_BIND_PORT=8081

OCI_BACKEND_BIND_HOST=127.0.0.1
OCI_BACKEND_BIND_PORT=8080

OCI_DB_BIND_HOST=127.0.0.1
OCI_DB_BIND_PORT=3306

OCI_MINIO_CONSOLE_BIND_HOST=127.0.0.1
OCI_MINIO_CONSOLE_BIND_PORT=9001
```

추가 운영 권장값:

```env
APP_SEED_ENABLED=false
TRAVEL_PRESIGNED_UPLOAD_ENABLED=false
H2_CONSOLE_ENABLED=false
```

비밀번호/키 값은 반드시 운영값으로 교체:

- `DB_PASSWORD`
- `DB_ROOT_PASSWORD`
- `MINIO_ROOT_PASSWORD`
- `JWT_KEY`

### 3-4. 최초 계정 bootstrap 주의

이 항목은 실제 운영에서 빠뜨리기 쉬운 부분입니다.

- `APP_SEED_ENABLED=false`
- 공개 회원가입 비활성화

이 두 조건이 동시에 걸리면 **첫 로그인 계정이 없어서 서비스 진입이 막힐 수 있습니다.**

따라서 최초 운영 준비 시에는 아래 중 하나가 반드시 필요합니다.

1. 첫 배포 시에만 `APP_SEED_ENABLED=true`로 올려 초기 계정을 만든 뒤 다시 끄기
2. DB에 관리자/초기 사용자 계정을 직접 insert 하기
3. 별도 bootstrap 관리자 스크립트 또는 운영 절차 마련

즉, 운영 문서에는 `.env`만 적는 것으로 끝내지 말고 **첫 사용자 생성 절차**를 함께 넣어야 합니다.

---

## 4. Docker Compose 구성 원칙

실제로 잘 동작했던 Compose 원칙은 다음과 같습니다.

### 4-1. frontend

- `127.0.0.1:8081 -> 80`
- 브라우저가 직접 붙지 않고 Nginx가 프록시

### 4-2. backend

- `127.0.0.1:8080 -> 8080`
- 외부 직접 노출 없음
- `/api`만 Nginx가 전달

### 4-3. mariadb

- 외부 미공개
- 필요 시 `127.0.0.1:3306`까지만 바인딩
- 내 PC 연결은 SSH 터널 권장

### 4-4. minio

- API는 Docker 내부망 전용
- Console이 필요할 때만 `127.0.0.1:9001`
- 인터넷 직접 공개 비권장

### 4-5. healthcheck

운영 Compose에는 가능하면 다음 healthcheck를 두는 것을 권장합니다.

- DB ping
- backend readiness endpoint
- frontend root 응답
- minio live health

---

## 5. Docker 실행

OCI 운영용 Compose 파일을 따로 두는 경우, **반드시 그 파일을 명시해서 실행**해야 합니다.

```bash
docker compose -f docker-compose.oci.yml up -d --build
```

상태 확인:

```bash
docker compose -f docker-compose.oci.yml ps
```

로그 확인:

```bash
docker compose -f docker-compose.oci.yml logs -f backend
docker compose -f docker-compose.oci.yml logs -f frontend
```

주의:

- 문서 마지막 요약 절차에서도 반드시 `-f docker-compose.oci.yml`을 유지해야 합니다.
- 기본 `docker-compose.yml`과 운영용 `docker-compose.oci.yml`의 포트 정책이 다를 수 있기 때문입니다.

---

## 6. 내부 동작 먼저 확인

도메인 연결 전에 먼저 로컬 바인딩이 살아 있는지 확인합니다.

프런트 응답 확인:

```bash
curl -I http://127.0.0.1:8081
```

백엔드 API 확인:

```bash
curl -i http://127.0.0.1:8081/api/auth/csrf
```

같은 서버 안에서 자기 자신의 공인 도메인으로 `curl` 했을 때는 hairpin NAT 성격 때문에 응답이 애매할 수 있습니다.  
그럴 때는 아래처럼 `Host` 헤더를 붙여 로컬 Nginx를 직접 확인하는 방식이 더 정확합니다.

```bash
curl -I -H "Host: www.example.com" http://127.0.0.1
```

---

## 7. DNS 설정

실제로 사용했던 기본 패턴:

- `A Record` / `www` / `<서버 공인 IP>`
- 필요 시 `A Record` / `@` / `<서버 공인 IP>`

서버에서 확인:

```bash
getent hosts www.example.com
```

### 7-1. `www`만 서비스할지, 루트 도메인도 함께 받을지 결정

여기서 중요한 점이 있습니다.

#### 선택지 A. `www.example.com`만 서비스

- 메인 주소를 `https://www.example.com`만 쓸 계획이라면
- 최소한 `www` A 레코드만 있어도 됩니다
- 이 경우 bare domain(`example.com`)을 굳이 HTTPS까지 처리하지 않아도 됩니다

#### 선택지 B. `example.com`과 `www.example.com` 둘 다 서비스

이 경우는 다음이 함께 필요합니다.

- `@` A 레코드
- 인증서 SAN 또는 별도 인증서에 `example.com` 포함
- Nginx 443 블록에서도 bare domain 처리

즉, `80`에서만 `example.com -> www.example.com` 리다이렉트를 걸고, `443`에서는 `www.example.com`만 처리하면  
사용자가 브라우저에서 바로 `https://example.com`을 입력했을 때 실패할 수 있습니다.

따라서 둘 중 하나로 정리해야 합니다.

1. 아예 `www`만 공식 주소로 사용하고 bare domain은 운영 범위에서 제외
2. 또는 bare domain용 `443` redirect 서버 블록과 인증서까지 함께 구성

---

## 8. OCI 보안 규칙

실제 운영에서 안전했던 기준:

- `22` 허용
- `80` 허용
- `443` 허용

권장:

- 인스턴스 전용 `NSG` 사용
- `8080`, `3306`, `9000`, `9001`은 외부 인바운드 비공개

주의:

- 공유 `Security List`를 수정하면 같은 서브넷의 다른 인스턴스에도 영향이 갈 수 있습니다.
- 이런 경우는 무조건 기존 룰을 삭제하기보다 **이 인스턴스 전용 NSG로 분리**하는 편이 안전합니다.

---

## 9. OCI 서버 OS 방화벽 이슈

이 부분이 실제 배포에서 매우 중요했습니다.

OCI 보안 규칙에서 `80`, `443`을 열었는데도 브라우저에서 접속이 안 되는 경우가 있었고,  
원인은 Ubuntu 이미지 안쪽의 `nftables / iptables` 룰이었습니다.

### 9-1. 현재 룰 확인

```bash
sudo nft list ruleset
```

또는:

```bash
sudo iptables -L INPUT -n --line-numbers
```

실제 문제 패턴:

- `22`만 허용
- 마지막에 나머지 입력 트래픽 `REJECT`
- 그래서 브라우저에서 `http://www.example.com` 접속이 실패

### 9-2. 80/443 허용

가장 단순한 방식:

```bash
sudo iptables -I INPUT -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 443 -j ACCEPT
```

확인:

```bash
sudo iptables -L INPUT -n --line-numbers
```

### 9-3. 재부팅 후에도 유지

```bash
sudo apt install -y iptables-persistent
sudo netfilter-persistent save
```

### 9-4. IPv6 사용 시 추가 확인

실제 적용은 IPv4 기준으로 충분했지만, 범용 가이드라면 IPv6도 같이 체크하는 편이 안전합니다.

- Nginx가 `[::]:80`, `[::]:443`까지 리슨 중이면
- OS 방화벽도 `ip6tables` 또는 `nftables`의 IPv6 체인을 같이 확인해야 합니다

즉, IPv6가 실제로 열려 있다면:

- `iptables`만 보지 말고
- `ip6tables` 또는 `nftables`의 `table ip6` 룰도 함께 확인합니다

---

## 10. HTTP 먼저 열기

HTTPS 전에 HTTP가 정상인지 먼저 확인하는 것이 훨씬 안전합니다.

예시:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name example.com www.example.com;

    client_max_body_size 40m;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto http;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port 80;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto http;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port 80;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
```

적용 경로 예시:

- `/etc/nginx/sites-available/example.com`
- `/etc/nginx/sites-enabled/example.com`

활성화:

```bash
sudo ln -sf /etc/nginx/sites-available/example.com /etc/nginx/sites-enabled/example.com
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx
```

---

## 11. HTTPS 적용

HTTP가 먼저 정상이어야 HTTPS 문제를 빠르게 좁힐 수 있습니다.

### 11-1. 인증서 파일 준비

실제로 많이 받는 파일 형태:

- `certificate.crt`
- `ca_bundle.crt`
- `private.key`

Nginx에는 보통 fullchain 형태로 합쳐서 사용합니다.

```bash
cat certificate.crt ca_bundle.crt > fullchain.crt
```

배치 예시:

```bash
sudo mkdir -p /etc/nginx/ssl/www.example.com
sudo cp fullchain.crt /etc/nginx/ssl/www.example.com/fullchain.crt
sudo cp private.key /etc/nginx/ssl/www.example.com/private.key
sudo chmod 600 /etc/nginx/ssl/www.example.com/private.key
sudo chmod 644 /etc/nginx/ssl/www.example.com/fullchain.crt
```

### 11-2. `www`만 공식 주소로 쓰는 HTTPS 예시

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name example.com www.example.com;

    return 301 https://www.example.com$request_uri;
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name www.example.com;

    ssl_certificate /etc/nginx/ssl/www.example.com/fullchain.crt;
    ssl_certificate_key /etc/nginx/ssl/www.example.com/private.key;

    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers on;

    client_max_body_size 40m;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port 443;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port 443;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
```

### 11-3. bare domain도 HTTPS로 처리해야 할 때

`example.com`도 함께 살릴 계획이면 아래가 추가로 필요합니다.

- 인증서에 `example.com` 포함
- bare domain용 `443` 서버 블록

예시:

```nginx
server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name example.com;

    ssl_certificate /etc/nginx/ssl/www.example.com/fullchain.crt;
    ssl_certificate_key /etc/nginx/ssl/www.example.com/private.key;

    return 301 https://www.example.com$request_uri;
}
```

즉, `80` 리다이렉트만으로는 부족할 수 있고, 직접 `https://example.com`으로 들어오는 경우까지 고려해야 합니다.

### 11-4. 적용 후 확인

```bash
sudo nginx -t
sudo systemctl reload nginx
sudo ss -ltnp | grep ':443 '
```

### 11-5. 인증서 도메인 일치 확인

```bash
openssl x509 -in /etc/nginx/ssl/www.example.com/fullchain.crt -noout -subject -issuer -ext subjectAltName
```

여기서 `DNS:www.example.com` 또는 필요한 SAN 값이 보여야 합니다.

---

## 12. 최종 점검 순서

1. 컨테이너 상태

```bash
docker compose -f docker-compose.oci.yml ps
```

2. 프런트 내부 응답

```bash
curl -I http://127.0.0.1:8081
```

3. 백엔드 API 내부 응답

```bash
curl -i http://127.0.0.1:8081/api/auth/csrf
```

4. 로컬 Nginx + Host 헤더 확인

```bash
curl -I -H "Host: www.example.com" http://127.0.0.1
```

5. 도메인 해석 확인

```bash
getent hosts www.example.com
```

6. 외부 브라우저 확인

```text
http://www.example.com
https://www.example.com
```

---

## 13. 배포 업데이트

실제 운영에서 안전했던 업데이트 방식:

```bash
cd ~/app
git pull
sudo docker compose -f docker-compose.oci.yml up -d --build
```

중요:

- 이 명령은 Docker 컨테이너만 다시 올립니다
- 아래 호스트 파일들은 자동으로 덮어쓰지 않습니다

  - `/etc/nginx/sites-available/...`
  - `/etc/nginx/sites-enabled/...`
  - `/etc/nginx/ssl/...`

즉, Nginx conf와 인증서 파일은 `git pull`로 날아가지 않습니다.

---

## 14. 내 PC에서 DB 접속

DB를 인터넷에 직접 열지 않고, SSH 터널로 접근하는 방식이 실제로 가장 안전하고 관리가 쉬웠습니다.

### 14-1. 내 PC에서 SSH 터널 생성

```bash
ssh -L 13306:127.0.0.1:3306 ubuntu@<OCI_PUBLIC_IP>
```

### 14-2. DB 툴 연결 정보

- Host: `127.0.0.1`
- Port: `13306`
- Database: 프로젝트 DB 이름
- Username: `.env`의 `DB_USER`
- Password: `.env`의 `DB_PASSWORD`

의미:

- 내 PC `127.0.0.1:13306`
- SSH 터널
- OCI 서버 `127.0.0.1:3306`

즉, DB를 외부에 직접 열지 않고도 Workbench, DBeaver, VS Code에서 안전하게 조회할 수 있습니다.

---

## 15. 실제로 중요했던 체크포인트

1. Docker 포트가 외부 공개되면 가능하면 `127.0.0.1` 바인딩으로 줄이기
2. 외부 공개는 호스트 Nginx `80/443`만 남기기
3. OCI 보안 규칙만 믿지 말고 OS 방화벽도 함께 확인하기
4. `ufw`가 없어도 `nftables / iptables`가 이미 막고 있을 수 있음
5. HTTPS 문제는 `443 리스닝 -> 인증서 도메인 일치 -> 방화벽` 순서로 좁히기
6. 같은 서버 안에서 공인 도메인 `curl`이 애매하면 `Host` 헤더 + `127.0.0.1`로 검증하기
7. DB는 직접 공개보다 SSH 터널이 훨씬 안전하고 관리하기 쉬움
8. 공유 Security List보다 인스턴스 전용 NSG가 운영상 안전함
9. 운영 Compose가 따로 있으면 요약 절차와 실제 명령 모두 동일하게 `-f docker-compose.oci.yml`을 유지하기
10. 공개 회원가입을 막아두었다면 첫 관리자/bootstrap 계정을 어떻게 확보할지 운영 절차에 반드시 포함하기

---

## 16. 다른 프로젝트에 적용할 때 바꾸면 되는 값

- Git 저장소 URL
- Docker Compose 파일명
- 프런트/백엔드 내부 포트
- 도메인 이름
- Nginx `server_name`
- 인증서 경로
- `.env` 비밀번호/시크릿 값
- DB 이름/계정
- MinIO 사용 여부
- bare domain HTTPS 처리 여부

---

## 17. 적용 순서 요약

1. OCI VM 생성
2. `git`, `docker.io`, `docker-compose-v2`, `nginx` 설치
3. 저장소 clone
4. `.env` 작성
5. `docker compose -f docker-compose.oci.yml up -d --build`
6. `curl http://127.0.0.1:8081` / `/api/auth/csrf` 확인
7. DNS에서 `www -> 공인 IP`
8. OCI 보안 규칙에서 `22/80/443` 확인
9. OS 방화벽에서 `80/443` 허용 + 저장
10. HTTP Nginx reverse proxy 적용
11. HTTP 접속 확인
12. 인증서 배치 + `fullchain.crt` 생성
13. HTTPS Nginx 설정 적용
14. `443` 리스닝 및 인증서 SAN 확인
15. 외부 브라우저에서 `https://www.example.com` 확인

이 순서대로 가면 다른 프로젝트에도 거의 그대로 재사용할 수 있습니다.
