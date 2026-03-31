#!/usr/bin/env bash
set -e

HOST_RCLONE_DIR="${DATA_OPS_RCLONE_CONFIG_HOST_CONTAINER_PATH:-/app/.config/rclone-host}"
TARGET_RCLONE_DIR="/app/.config/rclone"
TARGET_RCLONE_FILE="${TARGET_RCLONE_DIR}/rclone.conf"

mkdir -p /app/uploads /opt/calen-backup "${TARGET_RCLONE_DIR}"

if [ -r "${HOST_RCLONE_DIR}/rclone.conf" ]; then
  cp "${HOST_RCLONE_DIR}/rclone.conf" "${TARGET_RCLONE_FILE}"
  chown spring:spring "${TARGET_RCLONE_FILE}"
  chmod 600 "${TARGET_RCLONE_FILE}"
fi

chown -R spring:spring /app/uploads /opt/calen-backup "${TARGET_RCLONE_DIR}" 2>/dev/null || true

exec gosu spring java -Duser.dir=/app -jar /app/app.war
