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

require_file() {
  local path="$1"
  local label="$2"
  if [ ! -f "$path" ]; then
    echo "Missing ${label}: ${path}" >&2
    exit 1
  fi
}

require_file "$COMPOSE_FILE" "Docker Compose file"
require_file "$ENV_FILE" "compose env file"
require_file "$RCLONE_CONFIG" "rclone config"

mkdir -p "$BACKUP_DIR"

STAMP="$(date +%F-%H%M%S)"
FILE_NAME="calen-${STAMP}.sql.gz"
FILE_PATH="${BACKUP_DIR}/${FILE_NAME}"
TEMP_FILE="${FILE_PATH}.tmp"

cleanup_temp() {
  rm -f "$TEMP_FILE"
}
trap cleanup_temp EXIT

echo "Creating database backup: ${FILE_PATH}"
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T mariadb sh -c \
  'mariadb-dump -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" --default-character-set=utf8mb4 --single-transaction --quick --routines "$MARIADB_DATABASE"' \
  | gzip -c > "$TEMP_FILE"

mv "$TEMP_FILE" "$FILE_PATH"

echo "Uploading database backup to Google Drive: ${REMOTE_NAME}:${REMOTE_DIR}/${FILE_NAME}"
rclone --config "$RCLONE_CONFIG" copyto "$FILE_PATH" "${REMOTE_NAME}:${REMOTE_DIR}/${FILE_NAME}"

if [ "$DELETE_LOCAL_AFTER_UPLOAD" = "true" ]; then
  echo "Removing uploaded local backup: ${FILE_PATH}"
  rm -f "$FILE_PATH"
fi

if [[ "$LOCAL_RETENTION_DAYS" =~ ^[0-9]+$ ]]; then
  find "$BACKUP_DIR" -type f -name 'calen-*.sql.gz' -mtime +"$LOCAL_RETENTION_DAYS" -delete
fi

echo "Database backup completed: ${FILE_NAME}"
