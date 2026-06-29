# TravelLedger n8n AI Analysis

This folder contains the n8n deployment scaffold and an importable workflow for the ledger AI analysis feature.

## Server install

The default deployment binds n8n to `127.0.0.1:5678` on the server. This avoids exposing the n8n editor and webhook to the LAN by accident.

On `192.168.35.64`, copy this folder and run:

```bash
cd ~/travelledger-n8n-src
bash install-n8n.sh
```

From this repository on Windows, you can copy the files with an interactive password prompt:

```powershell
scp -r .\deploy\n8n test@192.168.35.64:~/travelledger-n8n-src
ssh test@192.168.35.64 "bash ~/travelledger-n8n-src/install-n8n.sh"
```

Do not put the SSH password, webhook key, n8n encryption key, or local LLM API key into this repository.

## Network mode

Localhost-only mode is the default:

```text
N8N_BIND_ADDRESS=127.0.0.1
N8N_HOST=127.0.0.1
WEBHOOK_URL=http://127.0.0.1:5678/
```

Use this when the Spring backend runs on the same server, or when you access n8n through an SSH tunnel.

LAN mode exposes n8n on the server network and should be used only after explicit approval and firewall/reverse-proxy planning:

```text
N8N_BIND_ADDRESS=192.168.35.64
N8N_HOST=192.168.35.64
WEBHOOK_URL=http://192.168.35.64:5678/
```

## Backend settings

For localhost-only mode on the same server:

```text
APP_LEDGER_AI_ENABLED=true
APP_LEDGER_AI_WORKFLOW_URL=http://127.0.0.1:5678/webhook/travelledger-ledger-ai
APP_LEDGER_AI_API_KEY=<same value as TRAVELLEDGER_AI_WEBHOOK_KEY>
APP_LEDGER_AI_API_KEY_HEADER=X-TravelLedger-AI-Key
```

For LAN mode:

```text
APP_LEDGER_AI_ENABLED=true
APP_LEDGER_AI_WORKFLOW_URL=http://192.168.35.64:5678/webhook/travelledger-ledger-ai
APP_LEDGER_AI_API_KEY=<same value as TRAVELLEDGER_AI_WEBHOOK_KEY>
APP_LEDGER_AI_API_KEY_HEADER=X-TravelLedger-AI-Key
```

`install-n8n.sh` generates `N8N_ENCRYPTION_KEY` and `TRAVELLEDGER_AI_WEBHOOK_KEY` in `$HOME/travelledger-n8n/.env` when they are blank.

## Workflow setup

`install-n8n.sh` imports `travelledger-ledger-ai-workflow.json` by default. Publishing the workflow is opt-in because it activates the production webhook:

```text
IMPORT_TRAVELLEDGER_AI_WORKFLOW=true
PUBLISH_TRAVELLEDGER_AI_WORKFLOW=false
```

Set `PUBLISH_TRAVELLEDGER_AI_WORKFLOW=true` in `$HOME/travelledger-n8n/.env` and rerun `install-n8n.sh` when the local LLM URL is ready.

The workflow expects an OpenAI-compatible local LLM API:

```text
LOCAL_LLM_BASE_URL=http://host.docker.internal:11434
LOCAL_LLM_API_KEY=
LOCAL_LLM_MODEL=gemma4:e12b
```

The workflow returns JSON in the shape expected by the backend:

```json
{
  "ok": true,
  "report": {
    "keySummary": "핵심 요약",
    "fullReport": "종합 보고서",
    "averageAmountInsight": "평균 지출 해석",
    "notableSpending": [],
    "regularSpending": [],
    "abnormalSpending": [],
    "topPaymentMethod": "지출 기준 최다 결제수단",
    "subscriptions": [],
    "fixedExpenses": [],
    "improvementActions": [],
    "comparisonFocus": []
  },
  "summary": "",
  "highlights": [],
  "warnings": [],
  "recommendations": [],
  "categoryInsights": [],
  "paymentInsights": [],
  "trendInsights": [],
  "unusualSpendingInsights": [],
  "fixedCostInsights": [],
  "nextPeriodForecast": "",
  "habitAssessment": ""
}
```