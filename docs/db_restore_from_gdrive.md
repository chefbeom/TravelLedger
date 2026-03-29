# Google Drive에 저장한 DB 백업 파일을 다시 MariaDB에 복구하는 가이드

이 문서는 `dbtogdrive.md` 방식으로 Google Drive에 저장한 `.sql.gz` 백업 파일을 다시 MariaDB에 적용하는 방법을 정리한 문서입니다.

기준 환경:

- 서버: OCI Ubuntu 24.04
- 앱 경로: `~/calen`
- Docker Compose 운영 중
- DB 컨테이너 이름: `calen-mariadb-1`
- Google Drive remote 이름: `db-backup`
- 백업 파일 예시: `calen-2026-03-29-000000.sql.gz`

중요:

- 운영 DB 복구는 현재 데이터를 덮어쓸 수 있습니다.
- 반드시 `테스트 복구 -> 실제 복구` 순서로 진행하는 걸 권장합니다.
- 복구 전에 가능하면 현재 상태도 한 번 더 백업하세요.

---

## 1. 복구할 파일 확인

먼저 Google Drive에 어떤 백업 파일이 있는지 확인합니다.

```bash
rclone lsf db-backup:calen-db-backups
```

예:

```text
calen-2026-03-28-000000.sql.gz
calen-2026-03-29-000000.sql.gz
```

복구할 파일명을 하나 정합니다.

---

## 2. 복구용 작업 폴더 만들기

```bash
mkdir -p /opt/calen-backup/restore
```

---

## 3. Google Drive에서 복구 파일 내려받기

예를 들어 `calen-2026-03-29-000000.sql.gz`를 복구한다고 가정하면:

```bash
rclone copy db-backup:calen-db-backups/calen-2026-03-29-000000.sql.gz /opt/calen-backup/restore/
```

로컬에 파일이 내려왔는지 확인:

```bash
ls -lh /opt/calen-backup/restore
```

---

## 4. 프로젝트 `.env` 값을 셸로 불러오기

현재 프로젝트 DB 이름과 계정을 그대로 사용하기 위해 `.env`를 읽어옵니다.

```bash
cd ~/calen
set -a
. ./.env
set +a
```

이후 아래 변수들이 셸에 올라옵니다.

- `DB_NAME`
- `DB_USER`
- `DB_PASSWORD`
- `DB_ROOT_PASSWORD`

확인하고 싶으면:

```bash
echo "$DB_NAME"
echo "$DB_USER"
```

비밀번호는 출력하지 않는 편이 좋습니다.

---

## 5. 테스트 DB에 먼저 복구하기

운영 DB를 바로 덮어쓰지 말고, 먼저 테스트 DB에 복구합니다.

### 5-1. 테스트 DB 이름 정하기

```bash
RESTORE_DB="${DB_NAME}_restore_test"
```

### 5-2. 테스트 DB 생성

```bash
docker exec calen-mariadb-1 mariadb -uroot -p"$DB_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS \`$RESTORE_DB\`; CREATE DATABASE \`$RESTORE_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

### 5-3. 백업 파일을 테스트 DB에 복구

```bash
gunzip -c /opt/calen-backup/restore/calen-2026-03-29-000000.sql.gz | docker exec -i calen-mariadb-1 mariadb -u"$DB_USER" -p"$DB_PASSWORD" "$RESTORE_DB"
```

여기서는 파일명만 실제 복구 대상 파일로 바꾸면 됩니다.

### 5-4. 테이블이 들어왔는지 확인

```bash
docker exec calen-mariadb-1 mariadb -u"$DB_USER" -p"$DB_PASSWORD" -e "USE \`$RESTORE_DB\`; SHOW TABLES;"
```

### 5-5. 사용자 수나 거래 수 확인

예:

```bash
docker exec calen-mariadb-1 mariadb -u"$DB_USER" -p"$DB_PASSWORD" -e "USE \`$RESTORE_DB\`; SELECT COUNT(*) AS users_count FROM app_users; SELECT COUNT(*) AS ledger_count FROM ledger_entries;"
```

이 단계가 정상이어야 운영 DB 복구로 넘어가는 것이 좋습니다.

---

## 6. 운영 DB 복구 전에 권장하는 추가 백업

복구 직전에 현재 상태도 한 번 더 덤프해두면 안전합니다.

예:

```bash
STAMP="$(date +%F-%H%M%S)"
docker exec calen-mariadb-1 sh -c "mariadb-dump -u\"$DB_USER\" -p\"$DB_PASSWORD\" \"$DB_NAME\"" | gzip > "/opt/calen-backup/restore/pre-restore-${STAMP}.sql.gz"
```

이 파일은 잘못 복구했을 때 되돌릴 수 있는 마지막 안전장치가 됩니다.

---

## 7. 실제 운영 DB 복구

주의:

- 이 단계부터는 현재 운영 데이터를 덮어씁니다.
- 서비스 사용자가 있다면 작업 전에 공지하는 것이 좋습니다.

### 7-1. 백엔드 중지

복구 중에는 앱이 DB를 건드리지 않게 백엔드를 잠시 멈춥니다.

```bash
sudo docker compose -f docker-compose.oci.yml stop backend
```

### 7-2. 운영 DB 삭제 후 재생성

```bash
docker exec calen-mariadb-1 mariadb -uroot -p"$DB_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

### 7-3. 백업 파일을 운영 DB에 복구

```bash
gunzip -c /opt/calen-backup/restore/calen-2026-03-29-000000.sql.gz | docker exec -i calen-mariadb-1 mariadb -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME"
```

### 7-4. 백엔드 재시작

```bash
sudo docker compose -f docker-compose.oci.yml start backend
```

---

## 8. 복구 후 확인

### 8-1. 컨테이너 상태 확인

```bash
sudo docker compose -f docker-compose.oci.yml ps
```

### 8-2. 백엔드 로그 확인

```bash
sudo docker compose -f docker-compose.oci.yml logs --tail=100 backend
```

### 8-3. DB 기본 확인

```bash
docker exec calen-mariadb-1 mariadb -u"$DB_USER" -p"$DB_PASSWORD" -e "USE \`$DB_NAME\`; SHOW TABLES;"
```

### 8-4. 웹 접속 확인

브라우저에서:

```text
https://www.innoutdrive.space
```

또는 현재 운영 도메인에 맞는 주소로 접속해서 아래를 확인합니다.

- 로그인 가능 여부
- 가계부 데이터 표시 여부
- 관리자 페이지 진입 여부

---

## 9. 자주 쓰는 변형 명령

### 최근 백업 하나만 확인

```bash
rclone lsf db-backup:calen-db-backups | tail -n 1
```

### restore 폴더 내용 보기

```bash
ls -lh /opt/calen-backup/restore
```

### 테스트 복구 DB 삭제

```bash
docker exec calen-mariadb-1 mariadb -uroot -p"$DB_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS \`${DB_NAME}_restore_test\`;"
```

---

## 10. 복구 실패 시 점검할 것

### 10-1. `Unknown database` 오류

- DB 생성 단계가 빠졌는지 확인
- `DB_NAME`이 제대로 불러와졌는지 확인

확인:

```bash
echo "$DB_NAME"
```

### 10-2. `Access denied` 오류

- `DB_USER`, `DB_PASSWORD`, `DB_ROOT_PASSWORD` 확인
- `.env`를 다시 불러왔는지 확인

### 10-3. `rclone` 다운로드 실패

- remote 이름 확인
- Google Drive 연결이 살아 있는지 확인

```bash
rclone lsd db-backup:
```

### 10-4. 앱은 뜨는데 로그인/데이터가 이상한 경우

- 백엔드 로그 확인
- 실제 복구한 파일이 맞는지 다시 확인
- 운영 DB가 아니라 테스트 DB에만 복구한 것은 아닌지 확인

---

## 11. 권장 운영 순서 요약

실제 운영에서는 아래 순서를 추천합니다.

1. 복구 대상 백업 파일 선택
2. Google Drive에서 서버로 다운로드
3. `.env` 불러오기
4. 테스트 DB에 먼저 복구
5. 테이블/건수 확인
6. 현재 운영 상태 추가 백업
7. 백엔드 중지
8. 운영 DB 재생성 후 복구
9. 백엔드 재시작
10. 앱 로그인/데이터 확인

---

## 12. 한 줄 요약

복구는 무조건 아래 원칙으로 진행하는 것이 안전합니다.

- 백업 파일 선택
- 테스트 DB로 먼저 검증
- 운영 DB는 마지막에 덮어쓰기
- 복구 후 앱까지 직접 확인

이 원칙만 지키면 Google Drive에 저장해둔 `.sql.gz` 백업 파일로 실제 서비스 DB를 안정적으로 되살릴 수 있습니다.

