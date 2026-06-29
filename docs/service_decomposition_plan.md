# Service Decomposition Plan

Updated: 2026-06-30

This plan keeps the next refactors small and reversible. The goal is not to split large services for style alone; it is to make security-sensitive behavior easier to test without changing public API contracts.

## Current Baseline

| Service | Current size | Main risk |
| --- | ---: | --- |
| `LedgerAiAnalysisService` | 1255 lines | AI payload creation, provider orchestration, duplicate suppression, history persistence, report mapping, metrics, and notification side effects live in one transaction boundary. |
| `TravelService` | 3278 lines | Plans, sharing, map snapshots, media upload completion, route/GPX handling, expense reflection, cache invalidation, public atlas reads, and exchange rates are mixed in one service. |

## Refactor Guardrails

| Guardrail | Rule |
| --- | --- |
| API stability | Controller request/response DTOs and endpoint behavior must remain unchanged unless a product task explicitly changes them. |
| Security first | Owner scope, CSRF assumptions, public-token checks, provider allowlists, and payload minimization must be preserved before and after every extraction. |
| Small slices | Extract one collaborator at a time and keep the original service as the orchestrator until tests prove the collaborator boundary. |
| Pure before side effects | Prefer extracting pure builders/mappers before classes that write history, send notifications, call providers, or invalidate caches. |
| Test before move | Add or keep focused tests around the behavior being moved, then move code behind the same assertions. |
| Bounded dependencies | New collaborators should depend only on the repositories/services they actually need. Avoid passing the whole original service state across. |
| Transaction clarity | Do not broaden `@Transactional` scope during extraction. New write collaborators should document whether they require caller-managed or local transactions. |

## Ledger AI Extraction Queue

| Phase | Candidate collaborator | Move out of `LedgerAiAnalysisService` | Test focus |
| --- | --- | --- | --- |
| 1 | `LedgerAiAnalysisPayloadBuilder` | Dataset-to-provider payload mapping, entry truncation, top expense limiting, `payloadMinimization`, output contract text. | Prompt-injection-as-data, text limits, overflow counts, provider payload shape. |
| 2 | `LedgerAiAnalysisReportMerger` | Fallback report creation, remote report merge, summary/highlight/warning fallback rules. | Invalid/partial provider responses still produce safe advice-only report fields. |
| 3 | `LedgerAiAnalysisPlanResolver` | Request mode/period/custom date validation and comparison range resolution. | Monthly/custom/comparison range boundaries and bad request messages. |
| 4 | `LedgerAiAnalysisHistoryCoordinator` | Recent completed duplicate suppression, failed/completed history persistence, provider/model identity. | 5-minute reuse, failed history on provider exception, provider-aware lookup keys. |
| 5 | `LedgerAiAnalysisOrchestrator` | Remote provider call sequence, timer/counter recording, notification side effects. | Provider failure metrics, notification metadata safety, no duplicate remote call on cache hit. |

### Ledger AI Exit Criteria

- The original service becomes a thin orchestration layer under roughly 400-600 lines.
- Payload, report, plan, and history collaborators have focused unit tests.
- Existing AI safety tests still cover prompt injection, status redaction, provider allowlist, payload minimization, duplicate suppression, and response schema validation.
- AI output remains advice/analysis only; no ledger mutation is introduced without explicit user confirmation.

## Travel Service Extraction Queue

| Phase | Candidate collaborator | Move out of `TravelService` | Test focus |
| --- | --- | --- | --- |
| 1 | `TravelMediaUploadCoordinator` | Prepare/complete record and memory media upload, presigned completion, thumbnail metadata mapping, media cache invalidation hooks. | Owner/record-scoped object keys, upload MIME/signature behavior, thumbnail path integrity. |
| 2 | `TravelMapQueryService` | My-map overview, marker bundle, photo cluster detail, public atlas read models. | Owner/public/share visibility and cache-key isolation. |
| 3 | `TravelShareService` | Share recipient search, share group CRUD, completed-plan share/cancel, public-share toggles. | Owner-only mutation, recipient validation, public/private visibility. |
| 4 | `TravelExpenseLedgerBridge` | Travel expense record creation/update/reflection to ledger and ledger entry reflection back to travel. | Owner scope, duplicate reflection prevention, ledger/travel consistency. |
| 5 | `TravelRouteService` | Route segment CRUD, GPX upload, route media cleanup. | Route owner scope, GPX validation, cleanup behavior. |
| 6 | `TravelExchangeRateService` | Exchange-rate lookup and response mapping. | Cache behavior, fallback defaults, unsupported currency handling. |

### Travel Exit Criteria

- `TravelService` keeps only high-level orchestration or is retired behind feature-specific services.
- Media, sharing, map, route, and ledger-bridge responsibilities can be tested without constructing the whole travel service graph.
- Existing controller endpoints and response DTOs remain stable during extraction.
- Cache invalidation behavior is documented per extracted collaborator.

## Suggested First Implementation Slices

1. Extract `LedgerAiAnalysisPayloadBuilder` because it is mostly pure and already has strong payload-minimization tests.
2. Extract `LedgerAiAnalysisReportMerger` because it makes provider response safety easier to test without repositories.
3. Extract `TravelMediaUploadCoordinator` because presigned object-key scope and upload validation are now covered by focused storage tests.
4. Extract `TravelMapQueryService` after map/share visibility tests are in place.
5. Extract write-heavy travel sharing and ledger bridge code only after owner-scope integration tests cover the moved behavior.

## Refactor Review Checklist

Before merging a service extraction:

1. Identify the exact methods moved and the public behavior that must not change.
2. Add or preserve tests that fail if owner scope, token checks, or provider safety changes.
3. Keep DTO contracts stable or document the API migration.
4. Confirm no new collaborator logs API keys, signed URLs, raw prompts, secondary PINs, or raw public tokens.
5. Confirm the original service has fewer responsibilities after the change, not just more indirection.