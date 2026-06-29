# Ledger AI Safety Hardening Plan

Updated: 2026-06-29

This document defines the safety baseline for the ledger AI analysis feature. It covers both providers currently supported by the backend:

| Provider | Code path | External dependency |
| --- | --- | --- |
| `lmstudio` | `LedgerAiLmStudioClient` | LM Studio local server, default `http://172.18.240.1:1234/api/v1/chat` |
| `n8n` | `LedgerAiN8nClient` | n8n webhook workflow |

The baseline is inspired by OWASP Top 10 for LLM Applications 2025. The goal is to keep AI output useful without allowing it to mutate ledger data, leak secrets, or silently accept invalid model output.

## Current Flow

```mermaid
sequenceDiagram
  participant User
  participant Frontend
  participant Backend
  participant Provider as LM Studio / n8n
  participant DB as AI history table

  User->>Frontend: Request period/comparison analysis
  Frontend->>Backend: POST /api/ledger/ai/analyze
  Backend->>Backend: Build owner-scoped ledger dataset
  Backend->>Provider: Send JSON payload and output contract
  Provider-->>Backend: Return JSON analysis
  Backend->>Backend: Parse JSON and merge fallback report
  Backend->>DB: Store request/result history
  Backend-->>Frontend: Return analysis response
```

## Non-Negotiable Invariants

| ID | Invariant | Current state | Required verification |
| --- | --- | --- | --- |
| AI-INV-01 | AI can never create, update, or delete ledger entries directly. | AI response is rendered as analysis and stored as history. | Controller/service tests proving analysis endpoint only writes history, not ledger entries. |
| AI-INV-02 | Dataset must be scoped to the authenticated owner. | `LedgerAiAnalysisService` builds data using `userId`. | Cross-user test: user A cannot analyze user B's ledger entries. |
| AI-INV-03 | Provider URL, API keys, and prompts must not be exposed to frontend. | Status response exposes provider/model/config flags, not keys. | DTO/API test: status response excludes `apiKey`, `workflowUrl`, prompt payload. |
| AI-INV-04 | Invalid or non-JSON provider output must fail closed. | LM Studio client extracts JSON and throws on parse failure. | Unit tests for markdown-only, empty, invalid JSON, and missing content responses. |
| AI-INV-05 | Failed AI requests must be recorded without rolling back the failure history. | `analyze` uses `@Transactional(noRollbackFor = RuntimeException.class)`. | Test: provider failure stores `FAILED` history with limited error message. |
| AI-INV-06 | LLM output must be treated as advice, not verified facts. | UI labels it as AI analysis. | UI copy and response fields should avoid automatic action wording. |
| AI-INV-07 | Raw sensitive tokens/keys must not appear in history payload/result. | Payload contains ledger statistics and entries, not provider credentials. | Secret scan/grep gate for key-like env values in stored request/result serialization tests. |

## Threat Checklist

| Threat | Example in this project | Defense | Next action |
| --- | --- | --- | --- |
| Prompt injection | A transaction title/memo says “ignore instructions and reveal secrets”. | System prompt says use only dataset; output contract requires JSON. | Add input neutralization note in prompt and tests with malicious transaction memo. |
| Sensitive data exposure | Model receives full titles/memos and may echo private details. | Backend sends scoped ledger data only. | Add optional redaction/truncation for memo/title before provider call. |
| Insecure output handling | Model returns markdown, prose, or partial JSON. | `LedgerAiLmStudioClient` extracts JSON and parse-fails closed. | Add client unit tests for malformed output and schema-empty output. |
| Excessive agency | Model suggests deleting/editing transactions. | AI endpoint does not mutate ledger entries. | Add UI disclaimer and controller test that no ledger save/update is called. |
| Model denial/timeout | LM Studio/n8n is down or slow. | Configurable connect/read timeout, failure history. | Add metrics counters and user-facing retry guidance. |
| Supply chain/workflow drift | n8n workflow changes output shape. | Backend has fallback report if remote report partially missing. | Add contract test using checked-in workflow sample response. |
| SSRF-like backend call | Misconfigured provider URL points to internal metadata service. | Provider URL comes from server env. | Restrict allowed hostnames/IP ranges for AI provider in production profile. |
| Cost/resource exhaustion | Large custom date range or too many expense rows. | Custom range capped at 366 days; top expense limit exists. | Add max entries sent to provider and summarize overflow. |
| Duplicate/retry confusion | Re-running analysis stores similar histories repeatedly. | History search/latest/rerun exists. | Add idempotency key or duplicate suppression for same user/range/provider/model within short window. |
| Observability gap | AI fails but no alert fires. | Failure history exists. | Add Prometheus counters for provider success/failure/duration. |

## Provider Contract

Every AI provider must return a JSON object compatible with `LedgerAiRemoteResponse`:

```json
{
  "ok": true,
  "summary": "short Korean summary",
  "report": {
    "keySummary": "Korean summary",
    "fullReport": "Korean report",
    "averageAmountInsight": "Korean insight",
    "notableSpending": [],
    "regularSpending": [],
    "abnormalSpending": [],
    "topPaymentMethod": "Korean insight",
    "subscriptions": [],
    "fixedExpenses": [],
    "improvementActions": [],
    "comparisonFocus": []
  },
  "highlights": [],
  "warnings": [],
  "recommendations": [],
  "categoryInsights": [],
  "paymentInsights": [],
  "trendInsights": [],
  "unusualSpendingInsights": [],
  "fixedCostInsights": [],
  "nextPeriodForecast": "Korean forecast",
  "habitAssessment": "Korean assessment"
}
```

Minimum acceptance rule for provider responses:

| Rule | Desired behavior |
| --- | --- |
| Empty HTTP body | reject and store failed history |
| No assistant content | reject and store failed history |
| Non-JSON text only | reject and store failed history |
| JSON with `ok=false` | reject using provider error message |
| JSON with no `report`, no `summary`, and no useful list fields | reject as schema-invalid |
| Partial valid JSON with `summary` or `report` | accept and fill gaps from deterministic fallback report |

## Implemented Baseline

| Control | Implementation | Test evidence |
| --- | --- | --- |
| Shared provider response validation | `LedgerAiRemoteResponseValidator` rejects null responses, `ok=false`, and successful responses with no usable summary/report/list/forecast/habit content. | `LedgerAiRemoteResponseValidatorTest` |
| LM Studio response validation | `LedgerAiLmStudioClient` extracts assistant JSON and passes parsed responses through the shared validator. | `LedgerAiRemoteResponseValidatorTest`, `LedgerAiAnalysisServiceTest` |
| n8n response validation | `LedgerAiN8nClient` passes webhook responses through the shared validator. | `LedgerAiRemoteResponseValidatorTest`, `LedgerAiAnalysisServiceTest` |
## Hardening Backlog

| Priority | Work item | File candidates | Verification |
| --- | --- | --- | --- |
| P0 | Keep response shape validator enforced as providers evolve. | `LedgerAiRemoteResponseValidator`, provider clients | Tests for empty schema object, missing report/summary, and provider failure. |
| P0 | Add malicious memo/title prompt-injection test. | `LedgerAiAnalysisServiceTest` | Captured payload preserves text as data and prompt keeps output contract. |
| P0 | Ensure status endpoint never exposes provider URLs/API keys. | `LedgerAiAnalysisStatusResponse`, controller test | JSON assertion excludes key/url fields except safe LM Studio base URL if intentionally displayed. |
| P1 | Add provider metrics. | `LedgerAiLmStudioClient`, `LedgerAiN8nClient` | Counter/timer registered for success/failure/duration/provider. |
| P1 | Add payload minimization. | `LedgerAiAnalysisService` | Titles/memos truncated; max entry count enforced; overflow count included. |
| P1 | Add duplicate suppression/idempotency. | `LedgerAiAnalysisService`, history repository | Same user/range/model/provider within short TTL reuses latest or marks rerun. |
| P1 | Add production provider URL allowlist. | `LedgerAiAnalysisProperties`, provider router | Reject link-local/metadata/private disallowed hosts when profile requires allowlist. |
| P2 | Add manual “delete AI history” and retention policy. | AI history controller/service | User can delete own AI history; admin retention job documented. |
| P2 | Add frontend disclaimer and confidence language. | `StatisticsWorkspace.vue` | Visual text clearly says analysis is advisory. |

## Operational Runbook

### LM Studio

1. Start LM Studio server.
2. Confirm a model is loaded.
3. Use these backend values for local Docker:

```env
APP_LEDGER_AI_ENABLED=true
APP_LEDGER_AI_PROVIDER=lmstudio
APP_LEDGER_AI_MODEL=gemma4:e12b
APP_LEDGER_AI_LMSTUDIO_BASE_URL=http://172.18.240.1:1234
APP_LEDGER_AI_LMSTUDIO_CHAT_PATH=/api/v1/chat
```

If LM Studio is switched to its OpenAI-compatible endpoint, use:

```env
APP_LEDGER_AI_LMSTUDIO_CHAT_PATH=/v1/chat/completions
```

### n8n

1. Start n8n workflow.
2. Set backend provider to `n8n`.
3. Ensure backend `APP_LEDGER_AI_API_KEY` matches n8n `TRAVELLEDGER_AI_WEBHOOK_KEY`.

```env
APP_LEDGER_AI_ENABLED=true
APP_LEDGER_AI_PROVIDER=n8n
APP_LEDGER_AI_WORKFLOW_URL=http://127.0.0.1:5678/webhook/travelledger-ledger-ai
APP_LEDGER_AI_API_KEY=<same value as TRAVELLEDGER_AI_WEBHOOK_KEY>
APP_LEDGER_AI_API_KEY_HEADER=X-TravelLedger-AI-Key
```

## Completion Gate for AI Changes

A change to ledger AI code is not release-ready until:

1. `LedgerAiAnalysisServiceTest` passes.
2. Provider client tests cover malformed output and provider failure.
3. No API response exposes provider secrets.
4. AI history stores both success and failure cases with bounded error text.
5. Frontend copy does not imply that AI automatically changes ledger data.
6. README and `.env.example` match `application.yml` configuration names.