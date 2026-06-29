# Service Decomposition Plan

Updated: 2026-06-30

This plan keeps the next refactors small and reversible. The goal is not to split large services for style alone; it is to make security-sensitive behavior easier to test without changing public API contracts.

## Current Baseline

| Service | Current size | Main risk |
| --- | ---: | --- |
| LedgerAiAnalysisService | 1121 lines | AI orchestration still owns payload creation, provider calls, duplicate suppression, history persistence, and report mapping; provider output contract text is isolated in LedgerAiOutputContract, Micrometer request metrics in LedgerAiAnalysisMetrics, AI analysis notification delivery in LedgerAiAnalysisNotifications, JSON history/result conversion in LedgerAiAnalysisJsonCodec, and text safety/length limiting in LedgerAiAnalysisTextSanitizer. |
| `TravelService` | 3278 lines | Plans, sharing, map snapshots, media upload completion, route/GPX handling, expense reflection, cache invalidation, public atlas reads, and exchange rates are mixed in one service. |

## CI Line Budget

| Service | Current baseline | CI budget | Policy |
| --- | ---: | ---: | --- |
| LedgerAiAnalysisService | 1121 lines | 1144 lines | Growth past the budget must extract payload, provider-call, report, history, or notification behavior before raising the limit. |
| `TravelService` | 3278 lines | 3300 lines | Growth past the budget must split media, map, share, route, exchange-rate, or ledger-bridge behavior before raising the limit. |

The budget is intentionally close to the current baseline so service decomposition behaves as a ratchet: new feature work should reduce or isolate responsibilities instead of adding more code to the large orchestrators.

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

## Responsibility Boundary Contract

These method groups should move together. A new feature should not add more logic to one of these groups unless the same change either extracts the target collaborator or records why extraction is intentionally deferred.

| Service | Boundary | Current anchors | Target collaborator | Must not own |
| --- | --- | --- | --- | --- |
| Ledger AI | Provider payload minimization | `buildPayload`, `buildPayloadMinimizationSummary`, `providerExpenseEntries`, `sanitizeProviderExpenseEntry`, `providerRecurringCandidates`, `LedgerAiAnalysisTextSanitizer` | `LedgerAiAnalysisPayloadBuilder`; text safety and provider length limiting now belong to `LedgerAiAnalysisTextSanitizer`. | History writes, notifications, provider HTTP calls, or report fallback text. |
| Ledger AI | Report merge and fallback copy | `buildReport`, `buildFallbackReport`, `buildFullReport`, `buildKeySummary`, `buildNotableSpending`, `buildImprovementActions` | `LedgerAiAnalysisReportMerger` | Provider calls, persistence, or request validation. |
| Ledger AI | Period and comparison planning | `resolvePlan`, `resolvePeriodRange`, `resolveComparisonRanges`, `validateCustomRange` | `LedgerAiAnalysisPlanResolver` | Repository reads, provider payload construction, or response mapping. |
| Ledger AI | History and duplicate suppression | `findReusableAnalysis`, `findLatestMatchingAnalysis`, `baseHistory`, `toSummary`, `LedgerAiAnalysisJsonCodec` | `LedgerAiAnalysisHistoryCoordinator`; JSON conversion now belongs to `LedgerAiAnalysisJsonCodec`. | Provider schema validation, notification delivery, or metric registration. |
| Ledger AI | Metrics and notification side effects | `LedgerAiAnalysisMetrics`, `LedgerAiAnalysisNotifications` | `LedgerAiAnalysisMetrics` owns Micrometer request metrics; `LedgerAiAnalysisNotifications` owns bounded completion/failure notification delivery. | Payload minimization, report text, or history query composition. |
| Travel | Media upload/download orchestration | `prepareMediaUploadInternal`, `completeMediaUploadInternal`, `getMediaDownload`, `getSharedMediaDownload`, `invalidateOwnedMediaDownloadCache` | `TravelMediaUploadCoordinator` | Share group mutation, map cluster rebuild, route CRUD, or exchange-rate lookup. |
| Travel | Map and photo cluster reads | `getMyMapOverview`, `getMyMapMarkerDetailBundle`, `getMyMapPhotoClusterDetail`, `resolveMyMapPhotoClusterDetail`, `refreshMyMapPhotoClusterSnapshot` | `TravelMapQueryService` | Upload completion, public-share mutation, or ledger reflection. |
| Travel | Sharing and public atlas visibility | `shareCompletedPlan`, `getPlanShares`, `cancelPlanShare`, `searchShareRecipients`, `updatePlanPublicShare`, `getSharedExhibits` | `TravelShareService` | Media byte serving, route storage, or expense reflection. |
| Travel | Ledger bridge | `reflectExpenseRecordToLedger`, `reflectLedgerEntryToTravelRecord`, `createExpenseRecord`, `updateExpenseRecord` | `TravelExpenseLedgerBridge` | Public atlas reads, media download cache, or exchange-rate provider calls. |
| Travel | Route and GPX lifecycle | `createRouteSegment`, `updateRouteSegment`, `uploadRouteGpxFiles`, `deleteRouteSegment` | `TravelRouteService` | Media upload completion, sharing, or ledger reflection. |
| Travel | Currency/exchange lookup | `getExchangeRates` | `TravelExchangeRateService` | Plan mutation, media cache invalidation, or public visibility decisions. |

## Decomposition Ratchet Rules

| Rule | Enforcement intent |
| --- | --- |
| Line budgets are a ratchet, not a target. | Raising a budget requires documenting why extraction cannot happen first. |
| New public methods in tracked services should be orchestration adapters. | Feature logic should land in the target collaborator named in the boundary contract. |
| New repository or external-client dependencies require a boundary decision. | If the dependency belongs to a target collaborator, extract or document a temporary exception. |
| Side-effect extraction needs focused safety evidence. | Owner scope, public-token checks, provider redaction, metrics labels, and notifications must remain bounded. |
| DTOs remain stable unless the product contract changes. | Refactors should not force frontend or API consumers to migrate. |

## Ledger AI Extraction Queue

| Phase | Candidate collaborator | Move out of `LedgerAiAnalysisService` | Test focus |
| --- | --- | --- | --- |
| 1 | `LedgerAiAnalysisPayloadBuilder` | Dataset-to-provider payload mapping, entry truncation, top expense limiting, and `payloadMinimization`; provider output contract text is already isolated in `LedgerAiOutputContract`. | Prompt-injection-as-data, text limits, overflow counts, provider payload shape. |
| 2 | `LedgerAiAnalysisReportMerger` | Fallback report creation, remote report merge, summary/highlight/warning fallback rules. | Invalid/partial provider responses still produce safe advice-only report fields. |
| 3 | `LedgerAiAnalysisPlanResolver` | Request mode/period/custom date validation and comparison range resolution. | Monthly/custom/comparison range boundaries and bad request messages. |
| 4 | `LedgerAiAnalysisHistoryCoordinator` | Recent completed duplicate suppression, failed/completed history persistence, provider/model identity. | 5-minute reuse, failed history on provider exception, provider-aware lookup keys. |
| 5 | `LedgerAiAnalysisOrchestrator` | Remote provider call sequence, timer/counter recording, notification side effects. | Provider failure metrics, notification metadata safety, no duplicate remote call on cache hit. |


### Ledger AI Extraction Progress

- 2026-06-30: Extracted provider output contract text into LedgerAiOutputContract, reducing LedgerAiAnalysisService to 1230 lines while keeping the existing provider payload contract surface stable.
- 2026-06-30: Extracted Micrometer AI request counter/timer and provider metric labeling into LedgerAiAnalysisMetrics, reducing LedgerAiAnalysisService to 1199 lines without changing controller DTOs or provider payloads.
- 2026-06-30: Extracted AI completion/failure notification delivery into LedgerAiAnalysisNotifications, reducing LedgerAiAnalysisService to 1155 lines while keeping notification metadata bounded.
- 2026-06-30: Extracted AI history/result JSON serialization into LedgerAiAnalysisJsonCodec, reducing LedgerAiAnalysisService to 1134 lines without changing persisted JSON shape.
- 2026-06-30: Extracted AI text safety and provider length limiting into LedgerAiAnalysisTextSanitizer, reducing LedgerAiAnalysisService to 1119 lines without changing payload text limits.
- 2026-06-30: Wired LedgerAiAnalysisService to the extracted metrics, JSON codec, text sanitizer, and notification collaborators so the decomposition boundary is enforced in constructor dependencies.
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

1. Continue `LedgerAiAnalysisPayloadBuilder` extraction by moving dataset-to-provider payload mapping and truncation now that output contract text is isolated.
2. Extract `LedgerAiAnalysisReportMerger` because it makes provider response safety easier to test without repositories.
3. Extract `TravelMediaUploadCoordinator` because presigned object-key scope and upload validation are now covered by focused storage tests.
4. Extract `TravelMapQueryService` after map/share visibility tests are in place.
5. Extract write-heavy travel sharing and ledger bridge code only after owner-scope integration tests cover the moved behavior.

## Plan Sync Gate

scripts/verify-service-decomposition-plan.ps1 checks that the Current Baseline and CI Line Budget rows match the tracked service files, that tracked services stay below the ratchet budget, and that the guardrails, responsibility boundary contract, ratchet rules, extraction queues, exit criteria, and refactor review checklist stay present. The CI service-decomposition-plan job runs this gate so large-service drift is visible before refactor work merges.

## Refactor Review Checklist

Before merging a service extraction:

1. Identify the exact methods moved and the public behavior that must not change.
2. Add or preserve tests that fail if owner scope, token checks, or provider safety changes.
3. Keep DTO contracts stable or document the API migration.
4. Confirm no new collaborator logs API keys, signed URLs, raw prompts, secondary PINs, or raw public tokens.
5. Confirm the original service has fewer responsibilities after the change, not just more indirection.
