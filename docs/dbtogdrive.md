# MariaDB DB를 Google Drive로 자동 백업하는 설정 가이드

이 문서는 이 프로젝트를 OCI Ubuntu 서버에서 Docker Compose로 운영하면서, MariaDB 덤프 파일을 매일 Google Drive로 자동 백업하도록 설정한 실제 과정을 정리한 문서입니다.

기준 환경:

- 서버: OCI Ubuntu 24.04
- 앱 경로: `~/calen`
- DB 컨테이너 이름: `calen-mariadb-1`
- 백업 폴더: `/opt/calen-backup`
- Google Drive remote 이름: `db-backup`

주의:

- 이 문서는 `Docker로 구동 중인 MariaDB`를 대상으로 합니다.
- `rclone` 토큰, DB 비밀번호, Google 인증 정보는 채팅/문서/스크린샷에 그대로 남기지 마세요.
- `rclone config show`는 토큰을 그대로 보여주므로 운영 환경에서는 주의해서 사용해야 합니다.

---

## 1. 백업 폴더 만들기

OCI 서버에서 아래 명령을 실행합니다.

```bash
sudo mkdir -p /opt/calen-backup
sudo chown $USER:$USER /opt/calen-backup
cd /opt/calen-backup
```

이 폴더는 아래 용도로 사용됩니다.

- 백업 스크립트 저장
- 임시 덤프 파일 저장
- 크론 로그 저장

---

## 2. rclone 설치

OCI 서버에서 설치합니다.

```bash
sudo apt update
sudo apt install -y rclone
```

설치 확인:

```bash
rclone version
```

---

## 3. Google Drive remote 만들기

OCI 서버에서 설정을 시작합니다.

```bash
rclone config
```

질문이 나오면 아래처럼 입력합니다.

### 3-1. remote 생성

```text
n
```

### 3-2. remote 이름

```text
db-backup
```

### 3-3. Storage 선택

`drive`를 선택합니다. 화면에 번호가 보이면 그 번호를 입력하면 됩니다.

### 3-4. client_id

그냥 엔터:

```text
Enter
```

### 3-5. client_secret

그냥 엔터:

```text
Enter
```

### 3-6. scope

아래 중 `3`을 선택합니다.

```text
3
```

의미:

- `drive.file`
- rclone이 만든 파일 범위 안에서 업로드/관리 가능
- 전체 Drive 접근보다 권한이 좁아서 더 안전함

### 3-7. root_folder_id

그냥 엔터:

```text
Enter
```

### 3-8. service_account_file

그냥 엔터:

```text
Enter
```

### 3-9. Edit advanced config?

```text
n
```

### 3-10. Use auto config?

```text
y
```

이 단계에서 브라우저 인증이 필요합니다.

### 3-11. Shared Drive 여부

아래 질문이 나오면 반드시 `n`을 선택합니다.

```text
Configure this as a Shared Drive (Team Drive)?
n
```

이 프로젝트의 백업은 일반 개인 Google Drive 기준입니다. `drive.file` scope로 `Shared Drive = y`를 선택하면 권한 부족 오류가 날 수 있습니다.

### 3-12. 설정 저장

```text
y
```

### 3-13. 설정 종료

```text
q
```

---

## 4. Google Drive 연결 확인

remote가 정상인지 확인합니다.

```bash
rclone lsd db-backup:
```

아무 에러 없이 끝나면 연결 성공입니다.

예:

```bash
rclone lsd db-backup:
```

출력이 비어 있어도 에러가 없으면 정상입니다.

---

## 5. DB 접속 정보 확인

프로젝트 `.env`에서 DB 정보를 확인합니다.

```bash
cd ~/calen
grep -E '^(DB_NAME|DB_USER|DB_PASSWORD)=' .env
```

이 값들은 문서나 채팅에 붙이지 말고, 스크립트 안에서만 읽도록 구성합니다.

---

## 6. 백업 스크립트 작성

스크립트 파일을 엽니다.

```bash
nano /opt/calen-backup/backup-to-gdrive.sh
```

아래 내용을 그대로 넣습니다.

```bash
#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="/home/ubuntu/calen"
BACKUP_DIR="/opt/calen-backup/files"
REMOTE_NAME="db-backup"
REMOTE_DIR="calen-db-backups"
RCLONE_CONFIG="/home/ubuntu/.config/rclone/rclone.conf"

mkdir -p "$BACKUP_DIR"

DB_NAME="$(grep '^DB_NAME=' "$PROJECT_DIR/.env" | cut -d= -f2-)"
DB_USER="$(grep '^DB_USER=' "$PROJECT_DIR/.env" | cut -d= -f2-)"
DB_PASSWORD="$(grep '^DB_PASSWORD=' "$PROJECT_DIR/.env" | cut -d= -f2-)"

STAMP="$(date +%F-%H%M%S)"
FILE_NAME="calen-${STAMP}.sql.gz"
FILE_PATH="$BACKUP_DIR/$FILE_NAME"

docker exec calen-mariadb-1 sh -c "mariadb-dump -u\"$DB_USER\" -p\"$DB_PASSWORD\" \"$DB_NAME\"" | gzip > "$FILE_PATH"

rclone --config "$RCLONE_CONFIG" copyto "$FILE_PATH" "${REMOTE_NAME}:${REMOTE_DIR}/$FILE_NAME"

find "$BACKUP_DIR" -type f -name 'calen-*.sql.gz' -mtime +7 -delete
```

### 꼭 확인할 값

아래 값은 서버 상황에 따라 수정해야 할 수 있습니다.

#### `PROJECT_DIR`

프로젝트 경로가 `~/calen`이 아닌 경우 수정합니다.

예:

```bash
PROJECT_DIR="/home/ubuntu/calen"
```

또는

```bash
PROJECT_DIR="/root/calen"
```

#### `RCLONE_CONFIG`

`rclone config`를 `ubuntu` 계정으로 만들었다면:

```bash
RCLONE_CONFIG="/home/ubuntu/.config/rclone/rclone.conf"
```

`root` 계정으로 만들었다면:

```bash
RCLONE_CONFIG="/root/.config/rclone/rclone.conf"
```

#### 컨테이너 이름

현재 프로젝트에서는 DB 컨테이너가 아래 이름입니다.

```bash
calen-mariadb-1
```

프로젝트명이나 Compose 설정이 다르면 이 이름이 바뀔 수 있으니, 아래 명령으로 먼저 확인해도 됩니다.

```bash
docker ps --format "table {{.Names}}\t{{.Image}}"
```

---

## 7. 스크립트 실행 권한 부여

```bash
chmod +x /opt/calen-backup/backup-to-gdrive.sh
```

---

## 8. 수동 실행 테스트

자동화 전에 반드시 한 번 수동 실행합니다.

```bash
/opt/calen-backup/backup-to-gdrive.sh
```

정상이라면:

- `/opt/calen-backup/files` 아래에 `.sql.gz` 파일 생성
- Google Drive의 `calen-db-backups` 폴더에 같은 파일 업로드

로컬 확인:

```bash
ls -lh /opt/calen-backup/files
```

Drive 확인:

```bash
rclone --config "/home/ubuntu/.config/rclone/rclone.conf" lsf db-backup:calen-db-backups
```

---

## 9. 매일 00:00 자동 실행(cron)

크론 편집기를 엽니다.

```bash
crontab -e
```

처음 실행이면 에디터 선택 화면이 나올 수 있습니다. 쉬운 쪽은 `nano`입니다.

맨 아래에 아래 한 줄을 추가합니다.

```cron
0 0 * * * /opt/calen-backup/backup-to-gdrive.sh >> /opt/calen-backup/backup.log 2>&1
```

의미:

- 매일 00:00 실행
- 실행 로그를 `/opt/calen-backup/backup.log`에 저장

등록 확인:

```bash
crontab -l
```

---

## 10. 운영 중 확인 명령

### 최근 백업 로그 보기

```bash
tail -n 50 /opt/calen-backup/backup.log
```

### 로컬 백업 파일 보기

```bash
ls -lh /opt/calen-backup/files
```

### Google Drive 업로드 목록 보기

```bash
rclone --config "/home/ubuntu/.config/rclone/rclone.conf" lsf db-backup:calen-db-backups
```

---

## 11. 보안 권장 사항

이 구성은 동작하지만, 운영에선 아래를 추가 권장합니다.

### 11-1. Drive에 올리기 전에 암호화

현재 파일은 `.sql.gz`라 압축만 된 상태입니다.  
가능하면 `gpg` 또는 `age`로 한 번 더 암호화해서 업로드하는 편이 안전합니다.

### 11-2. 복구 테스트

백업이 된다고 해서 복구가 되는 것은 아닙니다.  
반드시 테스트 DB에 한 번 복구해보는 절차를 운영 주기에 포함시키는 게 좋습니다.

### 11-3. 토큰 유출 주의

아래 명령은 토큰을 그대로 보여줄 수 있으니 주의합니다.

```bash
rclone config show
```

토큰이 유출됐다고 판단되면:

1. 해당 remote 삭제
2. Google 계정에서 rclone 연결 권한 철회
3. 다시 설정

---

## 12. 이 구성의 핵심 요약

이 프로젝트에서 실제로 동작한 백업 흐름은 아래입니다.

1. `mariadb-dump`로 현재 운영 DB를 덤프
2. `gzip`으로 압축
3. `rclone`으로 Google Drive 업로드
4. `cron`으로 매일 00:00 자동 실행

이 구조를 쓰면:

- OCI 서버가 망가져도 Drive에 오프사이트 백업이 남고
- DB 실수 삭제/손상 시 특정 시점으로 복구할 수 있으며
- 로컬에도 최근 7일 백업 파일이 남아 추가 확인이 가능합니다

