#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/travelledger-n8n}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKFLOW_ID="TravelLedgerLedgerAiAnalysis"
WORKFLOW_INPUT="/tmp/travelledger-ledger-ai-workflow.json"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required before installing n8n." >&2
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 is required before installing n8n." >&2
  exit 1
fi

mkdir -p "$APP_DIR"
cp "$SCRIPT_DIR/docker-compose.yml" "$APP_DIR/docker-compose.yml"
cp "$SCRIPT_DIR/travelledger-ledger-ai-workflow.json" "$APP_DIR/travelledger-ledger-ai-workflow.json"
cp "$SCRIPT_DIR/.env.example" "$APP_DIR/.env.example"

if [ ! -f "$APP_DIR/.env" ]; then
  cp "$SCRIPT_DIR/.env.example" "$APP_DIR/.env"
  echo "Created $APP_DIR/.env."
fi

generate_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    date +%s%N | sha256sum | awk '{print $1}'
  fi
}

if grep -q '^N8N_ENCRYPTION_KEY=$' "$APP_DIR/.env"; then
  key="$(generate_secret)"
  sed -i "s/^N8N_ENCRYPTION_KEY=$/N8N_ENCRYPTION_KEY=$key/" "$APP_DIR/.env"
  echo "Generated N8N_ENCRYPTION_KEY in $APP_DIR/.env."
fi

if grep -q '^TRAVELLEDGER_AI_WEBHOOK_KEY=$' "$APP_DIR/.env"; then
  webhook_key="$(generate_secret)"
  sed -i "s/^TRAVELLEDGER_AI_WEBHOOK_KEY=$/TRAVELLEDGER_AI_WEBHOOK_KEY=$webhook_key/" "$APP_DIR/.env"
  echo "Generated TRAVELLEDGER_AI_WEBHOOK_KEY in $APP_DIR/.env."
fi

cd "$APP_DIR"
docker compose pull
docker compose up -d

if grep -q '^IMPORT_TRAVELLEDGER_AI_WORKFLOW=true$' "$APP_DIR/.env"; then
  docker compose exec -T n8n n8n import:workflow --input="$WORKFLOW_INPUT"
  echo "Imported TravelLedger AI workflow."
fi

if grep -q '^PUBLISH_TRAVELLEDGER_AI_WORKFLOW=true$' "$APP_DIR/.env"; then
  if docker compose exec -T n8n n8n publish:workflow --id="$WORKFLOW_ID"; then
    echo "Published TravelLedger AI workflow."
  else
    docker compose exec -T n8n n8n update:workflow --id="$WORKFLOW_ID" --active=true
    echo "Activated TravelLedger AI workflow with legacy CLI command."
  fi
fi

docker compose ps

set -a
[ -f .env ] && . ./.env
set +a

echo "n8n should be available at ${WEBHOOK_URL:-http://127.0.0.1:5678/}"
echo "Workflow file is ready at $APP_DIR/travelledger-ledger-ai-workflow.json"
echo "Backend APP_LEDGER_AI_API_KEY must match TRAVELLEDGER_AI_WEBHOOK_KEY in $APP_DIR/.env"