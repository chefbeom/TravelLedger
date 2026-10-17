# Service Decomposition Plan

Updated: 2026-10-17

This plan keeps the next refactors small and reversible. The goal is not to split large services for style alone; it is to make security-sensitive behavior easier to test without changing public API contracts.

## Current Baseline

| Service | Current size | Main risk |
| --- | ---: | --- |
| `LedgerAiAnalysisService` | 410 lines | Delegates calendar planning, full dataset queries, bounded restart recovery, historical access, async admission and background execution to feature use cases. Shared draft/title and native payload/response logic are outside the service. Still owns synchronous inference orchestration and queue lifetime. DTO/v2/provider boundaries remain unchanged; not every legacy workflow has been migrated. |
| `TravelService` | 387 lines | Compatibility facade only: all fields are input use cases, including ReadTravelReferenceUseCase. Catalog policy is in TravelReferencePolicy; scoped data and quote DTO conversion are adapters. The original exchange provider/cache and transaction boundaries are unchanged. Full isolated regression (1318 tests) and WAR verification passed. |

## CI Line Budget

| Service | Current baseline | CI budget | Policy |
| --- | ---: | ---: | --- |
| `LedgerAiAnalysisService` | 410 lines | 412 lines | Growth past the budget must extract synchronous inference or queue orchestration before raising the limit. |
| `TravelService` | 387 lines | 393 lines | Keep this as a compatibility facade; business/I/O logic belongs in feature use cases. Compiled tests require all fields to remain input ports. |

The 2026-10-17 baseline reflects the current refactoring worktree. Limits were lowered, not raised, after typed provider boundaries, prepared-contract separation and Travel use-case extractions including owned aggregate reads and bidirectional ledger reflection. The narrow headroom (2 lines for Ledger AI and 6 for Travel) keeps decomposition as a ratchet: further growth should extract or isolate responsibilities instead of expanding the large orchestrators. See docs/backend_refactoring_progress.md for regression and incomplete-scope evidence.

## Refactor Guardrails

| Guardrail | Rule |
| --- | --- |
| API stability | Controller request/response DTOs and endpoint behavior must remain unchanged unless a product task explicitly changes them. |
| Security first | Owner scope, CSRF assumptions, public-token checks, provider allowlists, and payload minimization must be preserved before and after every extraction. |
| Small slices | Extract one collaborator at a time and keep the original service as the orchestrator until tests prove the collaborator boundary. |
| Pure before side effects | Prefer extracting pure builders/mappers before classes that write history, call providers, or invalidate caches. |
| Test before move | Add or keep focused tests around the behavior being moved, then move code behind the same assertions. |
| Bounded dependencies | New collaborators should depend only on the repositories/services they actually need. Avoid passing the whole original service state across. |
| Transaction clarity | Do not broaden `@Transactional` scope during extraction. New write collaborators should document whether they require caller-managed or local transactions. |

## Responsibility Boundary Contract

These method groups should move together. A new feature should not add more logic to one of these groups unless the same change either extracts the target collaborator or records why extraction is intentionally deferred.

| Service | Boundary | Current anchors | Target collaborator | Must not own |
| --- | --- | --- | --- | --- |
| Ledger AI | Provider payload minimization | `buildPayload`, `buildPayloadMinimizationSummary`, `providerExpenseEntries`, `sanitizeProviderExpenseEntry`, `providerRecurringCandidates`, `LedgerAiAnalysisTextSanitizer` | `LedgerAiAnalysisPayloadBuilder`; provider entry caps, recurring candidate truncation, and overflow counts now live outside `LedgerAiAnalysisService`. | History writes, provider HTTP calls, or report fallback text. |
| Ledger AI | Report merge and fallback copy | `LedgerLocalReportPolicy`, `LedgerLocalReportMapper`, `LedgerAiAnalysisReportMerger.merge`; legacy `buildReport`/`buildFallbackReport` are delegates. | Pure full-dataset grouping/copy plus a DTO boundary; capped provider requests are not report input. The merger retains remote-field precedence. | Provider calls, persistence, job state, request validation or changing the stored v2 shape. |
| Ledger AI | Period and comparison planning | `LedgerAnalysisCalendarPolicy`, `LedgerAnalysisPreparationData`; legacy `resolvePlan` delegates and sanitizes focus after successful calendar validation. | Pure calendar policy, lazy server-local date supplier and adapter DTO mapping. | Repository reads, provider payload construction, focus sanitization or response mapping. |
| Ledger AI | Full dataset preparation | `PrepareLedgerAnalysisDatasetUseCase`, `PrepareLedgerAnalysisDatasetService`, `LedgerAnalysisDatasetQueryAdapter` | Owner-scoped store port, existing statistics/projection queries and adapter value mapping; no new encompassing transaction. | Provider caps/calls, ledger mutation, job/history state or cache changes. |
| Ledger AI/OCR/Excel | Bounded restart recovery | `RecoverLedgerAiJobsUseCase`, `RecoverLedgerAiJobsService`, `LedgerAiRecoverySources`; all three scheduled entry methods delegate. | Pure traversal and family-specific eligibility/admission flow, with lazy runtime job ports over the same repositories/cursors/queues/leases. | New task registries, changed schedules, provider calls, ledger mutations, encompassing transactions or different terminal CAS/version rules. |
| Ledger AI | History access/results/matching | `ManageLedgerAnalysisHistoryUseCase`, `LedgerAnalysisHistoryQueryAdapter`, `LedgerAnalysisHistoryJsonAdapter`, `LedgerAnalysisHistoryMapper`; legacy helpers delegate and synchronous `findReusableAnalysis` retains its five-minute window. | Owner-scoped historical CRUD, pure fallback-versus-reusable policy and exact provider/model/range matching. Only CRUD creates REQUIRED transactions; internal matching/decoding retains its caller context. | Provider calls, terminal CAS/version updates, new caches, changed persisted JSON or comparison-preset filtering. |
| Ledger AI | Asynchronous admission and history drafts | `StartLedgerAnalysisUseCase`, `LedgerAnalysisAdmissions`, `LedgerAnalysisHistoryDrafts`, `LedgerAnalysisAdmissionPolicy` | Pure owner/config/lease/reuse/create/submit order over a request-local runtime. Reuse the validated owner, same leases/keys/task maps and existing finalizer. Sync and async share draft/title construction. | New registries, encompassing transactions, provider inference or changed status/CAS/queue cleanup rules. |
| Ledger AI | Background execution and shared formats | `ExecuteLedgerAnalysisUseCase`, `LedgerAnalysisFormats`, `LedgerAnalysisWorkTelemetry` | Typed workflow over owner-scoped status/payload writes, deferred v2 serialization/provider mapping, the existing provider/finalizer and bounded monitoring. Sync and async share native payload/response assembly. | New queues/registries, changed lease lifetime or terminal CAS, capped local report input, or provider inference inside a DB transaction. |
| Ledger AI | Status and metrics | `LedgerAiAnalysisStatusService`, `LedgerAiAnalysisMetrics` | `LedgerAiAnalysisStatusService` owns readiness/status response assembly; `LedgerAiAnalysisMetrics` owns Micrometer request metrics. | Payload minimization, report text, history query composition, or provider HTTP calls. |
| Travel | Media upload/download orchestration | `ManageOwnedTravelMediaUseCase`, `OwnedTravelMediaController`, `OwnedTravelMediaCache`, `TravelMediaHttpResponses`; legacy six methods are delegates. | Owned lifecycle is extracted. Shared/public authorization uses dedicated read use cases; media surfaces reuse HTTP rendering without reusing owner caches for authorization. | Share group mutation, map cluster rebuild internals, route CRUD, or exchange-rate lookup. |
| Travel | Map and photo cluster reads | `ReadOwnedTravelMapUseCase`, `ManageTravelPhotoClustersUseCase`, `RefreshTravelPhotoClustersUseCase` | `TransactionalOwnedTravelMap`, dedicated persistence/cache adapters and cluster use cases | Upload completion, public-share mutation, or ledger reflection. |
| Travel | Sharing management versus public atlas reads | `ManageTravelSharingUseCase`/`JpaTravelSharingAdapter` own management. `ReadTravelExhibitsUseCase`, `JpaTravelExhibitReadAdapter`, `TravelPlanDetailMapper` own recipient/public-media/authorized-record-share reads and detached details. `TravelMapLinksUseCase`/`JpaTravelMapLinkAdapter` own bearer links. `ReadTravelAtlasUseCase`/`JpaTravelAtlasAdapter` own atlas, cluster and community reads. | Published-first/recipient scope, distinct community rules and HMAC versus recipient URI selection are explicit; atlas has no owner-summary cache. | Media byte serving, route storage, or expense reflection. |
| Travel | Ledger bridge | `ReflectTravelLedgerUseCase` / `ReflectTravelLedgerService`; `reflectExpenseRecordToLedger` and `reflectLedgerEntryToTravelRecord` are compatibility delegates. | Writable transaction adapter + MANDATORY JPA/writer/`TravelBridgeEffects` adapters; the original ledger entry writer retains preparation/history. | Public atlas reads, media download cache, or exchange-rate provider HTTP inside the pure core. |
| Travel | Owned plan list/detail/portfolio | `ReadOwnedTravelPlansUseCase`, `ReadOwnedTravelPlansService`, `TransactionalOwnedTravelPlans`, `JpaOwnedTravelPlansAdapter`, `OwnedTravelPlansCache` | Separate SQL aggregate versus row-sum policy, scoped detached detail and legacy-wire cache codec; the same TravelSummaryCache owns SingleFlight. | Cross-user/shared/public scope, separate cache flight registries, upload mutations or provider HTTP. |
| Travel | Catalog and exchange-rate reads | `ReadTravelReferenceUseCase` / `TravelReferencePolicy`, `JpaTravelReferenceAdapter`, `TravelRateAdapter` | Pure sorting/deduplication/currency rules; scoped facts and the existing single exchange provider/cache instance. Readonly REQUIRED is unchanged. | Direct Spring/JPA/HTTP/DTO work in the core, new provider caching or silent currency/default behavior changes. |
| Travel | Route and GPX lifecycle | `WriteTravelRouteUseCase`, `ManageTravelRouteFilesUseCase`; legacy lifecycle methods delegate. | Dedicated route/file workflows and existing payload/storage adapters. | Media upload completion, sharing, or ledger reflection. |

## Decomposition Ratchet Rules

| Rule | Enforcement intent |
| --- | --- |
| Line budgets are a ratchet, not a target. | Raising a budget requires documenting why extraction cannot happen first. |
| New public methods in tracked services should be orchestration adapters. | Feature logic should land in the target collaborator named in the boundary contract. |
| New repository or external-client dependencies require a boundary decision. | If the dependency belongs to a target collaborator, extract or document a temporary exception. |
| Side-effect extraction needs focused safety evidence. | Owner scope, public-token checks, provider redaction, and metrics labels must remain bounded. |
| DTOs remain stable unless the product contract changes. | Refactors should not force frontend or API consumers to migrate. |

## Ledger AI Extraction Queue

| Phase | Candidate collaborator | Move out of `LedgerAiAnalysisService` | Test focus |
| --- | --- | --- | --- |
| 1 | `LedgerAiAnalysisPayloadBuilder` + `LedgerAnalysisFormats` (implemented) | Entry truncation, provider list limiting, recurring candidate truncation and `payloadMinimization` live in the builder. Shared native dataset-to-payload/response assembly lives in the format adapter, not the worker core. | Prompt-injection-as-data, text limits, overflow counts, provider payload shape and unchanged stored v2/full response JSON. |
| 2 | `LedgerLocalReportPolicy` + `LedgerAiAnalysisReportMerger` (implemented) | Local report/recurring/payment calculations use pure values; the existing merger owns remote versus fallback selection. | Independent 128-dataset full-JSON oracle, inclusive daily average/rounding, key/group/date order/top-ten rules, full 205/500 local entries versus capped provider input, unchanged v2 and merge precedence. |
| 3 | `LedgerAnalysisCalendarPolicy` + `PrepareLedgerAnalysisDatasetUseCase` (implemented) | Request calendar validation and full statistics/expense dataset query orchestration. | Frozen oracle: 1764 calendar requests and 64 datasets/query traces; lazy clock, inclusive 366-day range, weekly top-query suppression, owner scope and original per-source transactions. |
| 3a | `RecoverLedgerAiJobsUseCase` (implemented across analysis/image/Excel) | Bounded five-by-100 recovery traversal and family-specific admission decisions. Runtime bindings reuse existing cursors, tasks, leases and finalizers. | Independent three-loop oracle: 192 profiles of query/clock/lease/submission/error traces; continuation beyond 500, input-less rows, capacity distinctions and isolated H2 independent failure/lease contention. |
| 4 | `ManageLedgerAnalysisHistoryUseCase` + existing finalizer (access slice implemented) | Historical CRUD, summary/result mapping, stored-result fallback and reusable-result policy, completed/processing matching are separated. Admission still controls the unchanged 5-minute window and creates new history. | Frozen 128-row/96-filter/64-key full JSON/trace oracle, direct H2 scope/rollback/transactions, exact provider/model key and five-minute cutoff; corrupt results are visible but not reusable. |
| 4a | `StartLedgerAnalysisUseCase` (async slice implemented) | Owner/config checks, distributed admission, processing/completed reuse, pending creation/submission/failure and late summary materialization. Shared draft/title helpers preserve the old owner entity and record-string admission identity. | Frozen 128-flow/64-draft/key/title oracle, guard/error/close ordering, actual H2 lease contention, queue rejection cleanup and NOT_SUPPORTED caller rollback independence. |
| 5 | `ExecuteLedgerAnalysisUseCase` (background slice implemented) | Background status/load/prepare/conditional payload save, provider execution, lease fence, serialize-before-terminal and metric/error ordering. `LedgerAnalysisFormats` is shared by sync/async; sync inference and queue lifetime remain in the facade. | Frozen 128-flow/128-dataset JSON/trace oracle, nullable timer/lease loss/terminal CAS, direct-entry NOT_SUPPORTED and caller rollback independence. |


### Ledger AI Extraction Progress

- 2026-06-30: Extracted provider output contract text into LedgerAiOutputContract, reducing LedgerAiAnalysisService to 1230 lines while keeping the existing provider payload contract surface stable.
- 2026-06-30: Extracted Micrometer AI request counter/timer and provider metric labeling into LedgerAiAnalysisMetrics, reducing LedgerAiAnalysisService to 1199 lines without changing controller DTOs or provider payloads.
- 2026-06-30: Extracted AI history/result JSON serialization into LedgerAiAnalysisJsonCodec, reducing LedgerAiAnalysisService to 1134 lines without changing persisted JSON shape.
- 2026-06-30: Extracted AI text safety and provider length limiting into LedgerAiAnalysisTextSanitizer, reducing LedgerAiAnalysisService to 1119 lines without changing payload text limits.
- 2026-06-30: Wired LedgerAiAnalysisService to the extracted metrics, JSON codec, text sanitizer, and report-merger collaborators so the decomposition boundary is enforced in constructor dependencies.
- 2026-06-30: Extracted LedgerAiAnalysisReportMerger for remote report merge and fallback field selection, reducing LedgerAiAnalysisService to 1081 lines while keeping provider calls, persistence, and fallback copy generation in the orchestrating service.
- 2026-06-30: Extracted provider entry limiting and payloadMinimization counting into LedgerAiAnalysisPayloadBuilder, reducing the service while preserving provider payload safety limits.
- 2026-06-30: Extracted AI readiness/status response assembly into LedgerAiAnalysisStatusService and added focused secret/URL redaction coverage for status output.
### Ledger AI Exit Criteria

- The original service becomes a thin orchestration layer under roughly 400-600 lines.
- Payload, status, report, plan, and history collaborators have focused unit tests.
- Existing AI safety tests still cover prompt injection, status redaction, provider allowlist, payload minimization, duplicate suppression, and response schema validation.
- AI output remains advice/analysis only; no ledger mutation is introduced without explicit user confirmation.

## Travel Service Extraction Queue

| Phase | Candidate collaborator | Move out of `TravelService` | Test focus |
| --- | --- | --- | --- |
| 1 | `ManageOwnedTravelMediaUseCase` (owned slice implemented) | Prepare/complete record and memory media upload, delete/download and owner/id metadata cache are extracted. Shared/public authorization and parent-delete/in-flight cache races remain separate follow-ups. | Owner/record-scoped keys, MIME/signature behavior, batch rollback/link timing, headers and existing generic-original fallback versus thumbnail-only behavior. |
| 2 | `ReadOwnedTravelMapUseCase` / `ManageTravelPhotoClustersUseCase` / `ReadTravelAtlasUseCase` | Owned overview, marker bundle and photo-frame reads use `TransactionalOwnedTravelMap`, `JpaOwnedTravelMapReadAdapter` and dedicated cache/cluster adapters. Owned cluster detail/representative management, bearer links and authenticated atlas/community reads are extracted. | Owner/public/recipient scope, asynchronous HTTP, generation envelopes, shared SingleFlight invalidation, wait-outside-transaction, nearest-ten SQL/geometry, snapshot-only versus full GPX. |
| 2a | `ReadOwnedTravelPlansUseCase` (implemented) | Owned list/detail/portfolio use a pure workflow, MANDATORY scoped store and old-wire cache adapter sharing TravelSummaryCache. Legacy three methods are delegates. | Active owner before hit, SQL-versus-loaded-row semantics, grouping/rounding/clamp, full JSON/GPX, nullable legacy cache values, readonly loader and invalidation fencing. |
| 3 | `ManageTravelSharingUseCase` / `ReadTravelExhibitsUseCase` / `TravelMapLinksUseCase` / `ReadTravelAtlasUseCase` | Management, exhibit/public-media, bearer map-link and atlas/community routes use separate pure workflows and MANDATORY persistence adapters. Approved record-group bridges remain internal. Login settings depend on the map-link use case. | Owner/recipient/current-group scope, completed-plan versus community-photo versus bearer-link rules, published-first precedence, unchanged HMAC/token entropy, atomic scope validation, rollback, cache timing, privacy and full JSON. |
| 4 | `ReflectTravelLedgerUseCase` (implemented) | Both reflection POSTs use a typed pure workflow. JPA owns scoped facts/default references/mutation, LinkedLedgerWriter delegates existing create/update, TravelBridgeEffects reuses the shared cache and refresh batch. The former LedgerTravelBridgeService is removed. | Scoped lookup, null kind/shared compatibility, sequential upsert/trash exclusion, formatting/FX scale, rollback/history, reverse-only immediate/after-commit invalidation and per-owner dispatch. Existing concurrency semantics are preserved, not strengthened into a new uniqueness guarantee. |
| 5 | `WriteTravelRouteUseCase` / `ManageTravelRouteFilesUseCase` (implemented) | Route writes and GPX lifecycle use dedicated boundaries; compatibility delegates remain. | Route owner scope, GPX validation, compensation and cleanup behavior. |
| 6 | `ReadTravelReferenceUseCase` (implemented) | Catalog and rate reads use pure request policy plus owner-data and provider adapters. | Per-source sort/case-sensitive uniqueness, null kind, process locale, input insertion order, empty-segment errors, provider cache/fallback and unchanged readonly REQUIRED. |

### Travel Exit Criteria

- `TravelService` keeps only high-level orchestration or is retired behind feature-specific services.
- Media, sharing, map, route, and ledger-bridge responsibilities can be tested without constructing the whole travel service graph.
- Existing controller endpoints and response DTOs remain stable during extraction.
- Cache invalidation behavior is documented per extracted collaborator.

## Suggested First Implementation Slices

1. Continue `LedgerAiAnalysisPayloadBuilder` extraction by moving dataset-to-provider payload mapping and truncation now that output contract text is isolated.
2. Keep calendar/dataset preparation, bounded recovery, historical access and async admission stable; separate synchronous inference and task/worker execution while preserving original query order/transactions, leases, duplicate keys and terminal CAS.
3. Keep the migrated travel use cases and thin compatibility facade stable; audit remaining internal callers and parent-delete/in-flight media cache behavior.
4. Continue ledger read/history and OCR proposal/confirmation boundaries without changing approval or save behavior.
5. Complete schema/runtime and full-objective audits, keeping operating DB/storage/deployment actions separately approved.

## Plan Sync Gate

scripts/verify-service-decomposition-plan.ps1 checks that the Current Baseline and CI Line Budget rows match the tracked service files, that tracked services stay below the ratchet budget, and that the guardrails, responsibility boundary contract, ratchet rules, extraction queues, exit criteria, and refactor review checklist stay present. The CI service-decomposition-plan job runs this gate so large-service drift is visible before refactor work merges.

## Refactor Review Checklist

Before merging a service extraction:

1. Identify the exact methods moved and the public behavior that must not change.
2. Add or preserve tests that fail if owner scope, token checks, or provider safety changes.
3. Keep DTO contracts stable or document the API migration.
4. Confirm no new collaborator logs API keys, signed URLs, raw prompts, secondary PINs, or raw public tokens.
5. Confirm the original service has fewer responsibilities after the change, not just more indirection.
