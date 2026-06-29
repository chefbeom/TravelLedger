#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-/home/ubuntu/calen}"
COMPOSE_FILE="${COMPOSE_FILE:-${PROJECT_DIR}/docker-compose.oci.data.yml}"
ENV_FILE="${ENV_FILE:-${PROJECT_DIR}/.env.oci.data}"
BACKUP_ROOT="${BACKUP_ROOT:-/opt/calen-backup}"
BACKUP_DIR="${BACKUP_DIR:-${BACKUP_ROOT}/files}"
REMOTE_NAME="${REMOTE_NAME:-db-backup}"
REMOTE_DIR="${REMOTE_DIR:-calen-db-backups}"
RCLONE_CONFIG="${RCLONE_CONFIG:-/home/ubuntu/.config/rclone/rclone.conf}"
DELETE_LOCAL_AFTER_UPLOAD="${DELETE_LOCAL_AFTER_UPLOAD:-true}"
LOCAL_RETENTION_DAYS="${LOCAL_RETENTION_DAYS:-1}"
BACKUP_ENCRYPTION_MODE="${BACKUP_ENCRYPTION_MODE:-none}"
BACKUP_AGE_RECIPIENTS="${BACKUP_AGE_RECIPIENTS:-}"
BACKUP_GPG_RECIPIENT="${BACKUP_GPG_RECIPIENT:-}"
KEEP_PLAINTEXT_AFTER_ENCRYPTION="${KEEP_PLAINTEXT_AFTER_ENCRYPTION:-false}"

require_file() {
  local path="$1"
  local label="$2"
  if [ ! -f "$path" ]; then
    echo "Missing ${label}: ${path}" >&2
    exit 1
  fi
}

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

require_file "$COMPOSE_FILE" "Docker Compose file"
require_file "$ENV_FILE" "compose env file"
require_file "$RCLONE_CONFIG" "rclone config"

mkdir -p "$BACKUP_DIR"

STAMP="$(date +%F-%H%M%S)"
FILE_NAME="calen-${STAMP}.sql.gz"
FILE_PATH="${BACKUP_DIR}/${FILE_NAME}"
TEMP_FILE="${FILE_PATH}.tmp"
UPLOAD_FILE_NAME="$FILE_NAME"
UPLOAD_FILE_PATH="$FILE_PATH"
CHECKSUM_FILE_PATH=""
ENCRYPTED_FILE_PATH=""

cleanup_temp() {
  rm -f "$TEMP_FILE"
}
trap cleanup_temp EXIT

create_checksum() {
  local source_path="$1"
  local source_dir
  local source_name
  source_dir="$(dirname "$source_path")"
  source_name="$(basename "$source_path")"
  CHECKSUM_FILE_PATH="${source_path}.sha256"
  (cd "$source_dir" && sha256sum "$source_name" > "${source_name}.sha256")
}

encrypt_backup_if_requested() {
  local mode
  mode="$(printf '%s' "$BACKUP_ENCRYPTION_MODE" | tr '[:upper:]' '[:lower:]')"

  case "$mode" in
    none|"")
      echo "Backup encryption disabled by BACKUP_ENCRYPTION_MODE=none."
      ;;
    age)
      if ! command -v age >/dev/null 2>&1; then
        echo "BACKUP_ENCRYPTION_MODE=age requires the age command to be installed." >&2
        exit 1
      fi
      if [ -z "$BACKUP_AGE_RECIPIENTS" ]; then
        echo "BACKUP_ENCRYPTION_MODE=age requires BACKUP_AGE_RECIPIENTS." >&2
        exit 1
      fi
      local age_args=()
      local recipient
      IFS=',' read -ra recipients <<< "$BACKUP_AGE_RECIPIENTS"
      for recipient in "${recipients[@]}"; do
        recipient="$(trim "$recipient")"
        if [ -n "$recipient" ]; then
          age_args+=("-r" "$recipient")
        fi
      done
      if [ "${#age_args[@]}" -eq 0 ]; then
        echo "BACKUP_AGE_RECIPIENTS did not contain a usable age recipient." >&2
        exit 1
      fi
      ENCRYPTED_FILE_PATH="${FILE_PATH}.age"
      echo "Encrypting database backup with age: ${ENCRYPTED_FILE_PATH}"
      age "${age_args[@]}" -o "$ENCRYPTED_FILE_PATH" "$FILE_PATH"
      UPLOAD_FILE_PATH="$ENCRYPTED_FILE_PATH"
      UPLOAD_FILE_NAME="${FILE_NAME}.age"
      ;;
    gpg)
      if ! command -v gpg >/dev/null 2>&1; then
        echo "BACKUP_ENCRYPTION_MODE=gpg requires the gpg command to be installed." >&2
        exit 1
      fi
      if [ -z "$BACKUP_GPG_RECIPIENT" ]; then
        echo "BACKUP_ENCRYPTION_MODE=gpg requires BACKUP_GPG_RECIPIENT." >&2
        exit 1
      fi
      ENCRYPTED_FILE_PATH="${FILE_PATH}.gpg"
      echo "Encrypting database backup with gpg: ${ENCRYPTED_FILE_PATH}"
      gpg --batch --yes --trust-model always --recipient "$BACKUP_GPG_RECIPIENT" --output "$ENCRYPTED_FILE_PATH" --encrypt "$FILE_PATH"
      UPLOAD_FILE_PATH="$ENCRYPTED_FILE_PATH"
      UPLOAD_FILE_NAME="${FILE_NAME}.gpg"
      ;;
    *)
      echo "Invalid BACKUP_ENCRYPTION_MODE=${BACKUP_ENCRYPTION_MODE}. Use none, age, or gpg." >&2
      exit 1
      ;;
  esac

  if [ "$mode" != "none" ] && [ "$mode" != "" ] && [ "$KEEP_PLAINTEXT_AFTER_ENCRYPTION" != "true" ]; then
    echo "Removing plaintext backup after encryption: ${FILE_PATH}"
    rm -f "$FILE_PATH"
  fi
}

echo "Creating database backup: ${FILE_PATH}"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T mariadb sh -c \
  'mariadb-dump -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" --default-character-set=utf8mb4 --single-transaction --quick --routines "$MARIADB_DATABASE"' \
  | gzip -c > "$TEMP_FILE"

mv "$TEMP_FILE" "$FILE_PATH"
encrypt_backup_if_requested
create_checksum "$UPLOAD_FILE_PATH"

if [ -n "$CHECKSUM_FILE_PATH" ]; then
  echo "Created checksum sidecar: ${CHECKSUM_FILE_PATH}"
fi

echo "Uploading database backup to Google Drive: ${REMOTE_NAME}:${REMOTE_DIR}/${UPLOAD_FILE_NAME}"
rclone --config "$RCLONE_CONFIG" copyto "$UPLOAD_FILE_PATH" "${REMOTE_NAME}:${REMOTE_DIR}/${UPLOAD_FILE_NAME}"

if [ -n "$CHECKSUM_FILE_PATH" ]; then
  echo "Uploading database backup checksum to Google Drive: ${REMOTE_NAME}:${REMOTE_DIR}/${UPLOAD_FILE_NAME}.sha256"
  rclone --config "$RCLONE_CONFIG" copyto "$CHECKSUM_FILE_PATH" "${REMOTE_NAME}:${REMOTE_DIR}/${UPLOAD_FILE_NAME}.sha256"
fi

if [ "$DELETE_LOCAL_AFTER_UPLOAD" = "true" ]; then
  echo "Removing uploaded local backup artifacts for: ${UPLOAD_FILE_NAME}"
  rm -f "$FILE_PATH" "$UPLOAD_FILE_PATH" "$CHECKSUM_FILE_PATH"
fi

if [[ "$LOCAL_RETENTION_DAYS" =~ ^[0-9]+$ ]]; then
  find "$BACKUP_DIR" -type f -name 'calen-*.sql.gz*' -mtime +"$LOCAL_RETENTION_DAYS" -delete
fi

echo "Database backup completed: ${UPLOAD_FILE_NAME}"
