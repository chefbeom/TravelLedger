#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../../.." && pwd)"
ENV_FILE="${DATA_ENV_FILE:-${REPO_ROOT}/.env.oci.data}"
COMPOSE_FILE="${DATA_COMPOSE_FILE:-${REPO_ROOT}/docker-compose.oci.data.yml}"
MARIADB_SERVICE="${DATA_DB_SERVICE:-mariadb}"
MINIO_BOOTSTRAP_SERVICE="${DATA_MINIO_BOOTSTRAP_SERVICE:-minio-init}"

usage() {
  cat <<'EOF'
Usage:
  ./deploy/oci/scripts/provision-project-tenant.sh <project_slug> <login_id> <password> [db_name] [bucket_name]

Examples:
  ./deploy/oci/scripts/provision-project-tenant.sh fileinnout testid1234 testpw1234
  ./deploy/oci/scripts/provision-project-tenant.sh fileinnout testid1234 testpw1234 fileinnout_db fileinnout-bucket

What it does:
  1. Creates a MariaDB database for the project.
  2. Creates or updates a MariaDB user and grants privileges on that database only.
  3. Creates a MinIO bucket for the project.
  4. Creates or recreates a MinIO user with the same login/password.
  5. Creates or recreates a bucket-scoped MinIO policy and attaches it to that user.

Defaults:
  - db_name is derived from project_slug
  - bucket_name is derived from project_slug

Requirements:
  - Run this on the data server where docker-compose.oci.data.yml exists.
  - .env.oci.data must contain valid DB root and MinIO root credentials.
EOF
}

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

sanitize_db_name() {
  local value
  value="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9_]+/_/g; s/_+/_/g; s/^_+|_+$//g')"
  if [[ -z "$value" ]]; then
    value="project_db"
  fi
  if [[ "$value" =~ ^[0-9] ]]; then
    value="app_${value}"
  fi
  printf '%.64s' "$value"
}

sanitize_bucket_name() {
  local value
  value="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9.-]+/-/g; s/[.-]+/-/g; s/^-+|-+$//g')"
  if [[ -z "$value" ]]; then
    value="project-bucket"
  fi
  if [[ ${#value} -lt 3 ]]; then
    value="${value}$(printf '%*s' $((3 - ${#value})) '' | tr ' ' '0')"
  fi
  if [[ ${#value} -gt 63 ]]; then
    value="${value:0:63}"
    value="${value%-}"
  fi
  printf '%s' "$value"
}

validate_login_id() {
  local value="$1"
  [[ "$value" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$ ]] \
    || die "login_id must start with an alphanumeric character and use only letters, numbers, dot, underscore, or hyphen (3-128 chars)."
}

validate_password() {
  local value="$1"
  [[ ${#value} -ge 8 ]] || die "password must be at least 8 characters."
  [[ ! "$value" =~ [[:space:]] ]] || die "password must not contain whitespace."
  [[ "$value" != *"'"* ]] || die "password must not contain a single quote (')."
}

sql_escape_single_quotes() {
  printf '%s' "$1" | sed "s/'/''/g"
}

require_command docker

if [[ $# -lt 3 || $# -gt 5 ]]; then
  usage
  exit 1
fi

PROJECT_SLUG="$1"
LOGIN_ID="$2"
PASSWORD="$3"
DB_NAME="${4:-}"
BUCKET_NAME="${5:-}"

validate_login_id "$LOGIN_ID"
validate_password "$PASSWORD"

if [[ ! -f "$ENV_FILE" ]]; then
  die "Missing env file: $ENV_FILE"
fi

if [[ ! -f "$COMPOSE_FILE" ]]; then
  die "Missing compose file: $COMPOSE_FILE"
fi

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

: "${DB_ROOT_PASSWORD:?DB_ROOT_PASSWORD is required in ${ENV_FILE}}"
: "${MINIO_ROOT_USER:?MINIO_ROOT_USER is required in ${ENV_FILE}}"
: "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD is required in ${ENV_FILE}}"
: "${MINIO_API_INTERNAL_URL:?MINIO_API_INTERNAL_URL is required in ${ENV_FILE}}"

DB_NAME="${DB_NAME:-$(sanitize_db_name "$PROJECT_SLUG")}"
BUCKET_NAME="${BUCKET_NAME:-$(sanitize_bucket_name "$PROJECT_SLUG")}"
POLICY_NAME="${BUCKET_NAME}-rw"

COMPOSE_CMD=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")

printf 'Provisioning tenant...\n'
printf '  project_slug : %s\n' "$PROJECT_SLUG"
printf '  db_name      : %s\n' "$DB_NAME"
printf '  bucket_name  : %s\n' "$BUCKET_NAME"
printf '  login_id     : %s\n' "$LOGIN_ID"
printf '  compose_file : %s\n' "$COMPOSE_FILE"
printf '  env_file     : %s\n' "$ENV_FILE"

LOGIN_SQL="$(sql_escape_single_quotes "$LOGIN_ID")"
PASSWORD_SQL="$(sql_escape_single_quotes "$PASSWORD")"

cat <<SQL | "${COMPOSE_CMD[@]}" exec -T "$MARIADB_SERVICE" mariadb -uroot "-p${DB_ROOT_PASSWORD}"
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${LOGIN_SQL}'@'%' IDENTIFIED BY '${PASSWORD_SQL}';
ALTER USER '${LOGIN_SQL}'@'%' IDENTIFIED BY '${PASSWORD_SQL}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${LOGIN_SQL}'@'%';
FLUSH PRIVILEGES;
SQL

cat <<'SCRIPT' | "${COMPOSE_CMD[@]}" run --rm --no-deps \
  -e MINIO_API_INTERNAL_URL="$MINIO_API_INTERNAL_URL" \
  -e MINIO_ROOT_USER="$MINIO_ROOT_USER" \
  -e MINIO_ROOT_PASSWORD="$MINIO_ROOT_PASSWORD" \
  -e TENANT_USER="$LOGIN_ID" \
  -e TENANT_PASSWORD="$PASSWORD" \
  -e TENANT_BUCKET="$BUCKET_NAME" \
  -e TENANT_POLICY="$POLICY_NAME" \
  --entrypoint /bin/sh "$MINIO_BOOTSTRAP_SERVICE"
set -euo pipefail

mc alias set local "$MINIO_API_INTERNAL_URL" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc mb --ignore-existing "local/$TENANT_BUCKET"

cat > /tmp/tenant-policy.json <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetBucketLocation",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::$TENANT_BUCKET"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject",
        "s3:AbortMultipartUpload",
        "s3:ListMultipartUploadParts"
      ],
      "Resource": [
        "arn:aws:s3:::$TENANT_BUCKET/*"
      ]
    }
  ]
}
JSON

if mc admin user info local "$TENANT_USER" >/dev/null 2>&1; then
  mc admin user rm local "$TENANT_USER"
fi

mc admin user add local "$TENANT_USER" "$TENANT_PASSWORD"

if mc admin policy info local "$TENANT_POLICY" >/dev/null 2>&1; then
  mc admin policy rm local "$TENANT_POLICY"
fi

mc admin policy create local "$TENANT_POLICY" /tmp/tenant-policy.json
mc admin policy attach local "$TENANT_POLICY" --user "$TENANT_USER"
SCRIPT

cat <<EOF

Done. Use the following values in the new project's app env:

DB_NAME=${DB_NAME}
DB_USER=${LOGIN_ID}
DB_PASSWORD=${PASSWORD}
MINIO_ROOT_USER=${LOGIN_ID}
MINIO_ROOT_PASSWORD=${PASSWORD}
MINIO_CLOUD_BUCKET=${BUCKET_NAME}
MINIO_API_INTERNAL_URL=${MINIO_API_INTERNAL_URL}
MINIO_PUBLIC_API=${MINIO_PUBLIC_API:-https://minio.example.com}

Quick example:
  ./deploy/oci/scripts/provision-project-tenant.sh fileinnout testid1234 testpw1234
EOF
