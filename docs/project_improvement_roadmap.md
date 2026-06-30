# Project Improvement Roadmap

Updated: 2026-06-30

This roadmap turns the current improvement analysis into an implementation queue. It is intentionally practical: each item names the value, first implementation slice, and verification evidence.

## Priority Model

| Priority | Meaning |
| --- | --- |
| P0 | Risk reduction or release safety. Do before large feature expansion. |
| P1 | High-value product/operations work. Do after P0 baseline is stable. |
| P2 | Strategic enhancements. Do when core workflows are reliable. |

## P0: Safety and Reliability Foundation

| Workstream | Why it matters | First slice | Evidence of done |
| --- | --- | --- | --- |
| Security baseline | Auth, remember-me, CSRF, admin, sharing, upload, presigned URLs, and AI keys are mixed across domains. | Use `docs/security_baseline_checklist.md` plus `docs/remember_me_security_review.md` as release checklist, add P0 tests, and keep presigned object-key scope tests current. | Security-focused backend tests cover admin/share/presigned guardrails when run; remember-me restore is covered and logout/revocation tests are queued; public allowlist is reviewed. |
| AI safety | Ledger AI sends sensitive spending data to LM Studio or n8n and stores results. | Provider response validation, LM Studio prompt hardening, shared advice-only output contract, external workflow metrics, provider payload minimization, 5-minute duplicate suppression, frontend-generated bounded clientRequestId request metadata, same-JVM in-flight duplicate requests are serialized, provider URL allowlist controls, status-secret redaction tests, allowlist/duplicate-suppression/payload contract tests, and `docs/ai_provider_safety_contract.md` plus CI gate are in place; next persist durable client idempotency keys. | Provider failures store failed history; invalid JSON/schema is rejected, provider failure/latency can be alerted, provider-facing entry data is capped/truncated, quick retries reuse completed results and same-JVM double submits serialize before provider calls, AI status hides secrets/URLs, providers are told not to claim ledger mutations, and production can restrict AI provider hosts. |
| Admin audit logging | High-risk admin backup, restore, user, throttle, and drive-admin mutations need safe forensic detail. | `AdminController` now strips backup/restore audit details to base file names before `ADMIN_ACTION` logging; `docs/admin_audit_log_contract.md` and `scripts/verify-admin-audit-contract.ps1` keep action codes, safe-detail rules, and CI gate wiring aligned. | New admin-like mutations must add a bounded action code, safe detail shape, and focused audit evidence before release. |
| Admin/share authorization tests | Admin APIs and public links are high-impact. | Drive public download links, direct share permissions/access logs, Travel public media tokens, shared exhibits, and privacy revocation are now mapped in `docs/public_share_authorization_contract.md`; next add frontend owner access-log view and Travel public media expiry/rotation. | CI gates reject drift in invalid public token handling, raw-token-free logs, owner-only log reads, `VIEW` download denial, Travel media token-pair checks, and current-user revocation scope. |
| Upload validation | Drive/travel/family/support/OCR upload paths have different risk profiles. | OCR, Drive, travel, family, and support upload validation anchors are now mapped in `docs/file_upload_security_contract.md`; `docs/media_processing_queue_contract.md` now separates the original upload lane, thumbnail backfill/reprocessing lane, and future video/transcode lane. Next define remaining per-feature max sizes and shared policy helpers. | CI gates reject drift in upload validation, presigned object-key scope, fake image handling, thumbnail fail-closed evidence, and media queue/lane ownership. |
| Configuration sync | `.env.example`, `.env.oci.app.example`, `application.yml`, compose files can drift. | `docs/env_configuration_contract.md` now defines required DB, auth/session, MinIO, OCR/AI, Redis, travel media, and backup variable groups; `scripts/verify-env-sync.ps1` also checks LM Studio `/api/v1/chat` and `/api/v1/models`, provider allowlist defaults, placeholder secret values, and OCI production-safety toggles. | CI fails on missing, duplicate, malformed, unallowlisted, unsafe placeholder, stale contract, or unsafe provider endpoint names. |

## P1: Operations and Maintainability

| Workstream | Why it matters | First slice | Evidence of done |
| --- | --- | --- | --- |
| Service decomposition | LedgerAiAnalysisService is 1066 lines and TravelService is 3278 lines, so risky behavior is hard to isolate. | LedgerAiOutputContract owns provider output contract text, LedgerAiAnalysisMetrics owns AI request metrics, LedgerAiAnalysisNotifications owns AI notification delivery, LedgerAiAnalysisJsonCodec owns AI history/result JSON conversion, LedgerAiAnalysisTextSanitizer owns AI text safety/length limiting, LedgerAiAnalysisPayloadBuilder owns provider entry caps and payload minimization counts, and LedgerAiAnalysisService is wired to those extracted collaborators through constructor dependencies; `docs/service_decomposition_plan.md` tracks responsibility boundaries, ratchet rules, and current service line budgets before extracting pure AI payload/report/plan collaborators and travel media/map/share collaborators. | Each extraction leaves controller DTOs stable, stays within the ratchet budget, and adds focused tests for owner scope, AI safety, cache invalidation, and side effects. |
| DB migration management | `*SchemaUpdater` classes are convenient but weak for production rollback/audit. | Flyway is wired behind `DB_MIGRATION_ENABLED`; `docs/db_migration_strategy.md` now freezes new startup DDL, inventories six temporary legacy updater exceptions, and `scripts/verify-db-migrations.ps1` rejects unexpected `ApplicationRunner`/`CommandLineRunner` DDL. | New schema change has a versioned migration, updated inventory/evidence rows, no new startup schema mutation, and repeatable local/staging startup evidence. |
| Observability/alerts | Prometheus/Grafana exists, but alerting is the next operational jump. | Prometheus alert rules cover AI/OCR failures, external workflow latency, backup failures/staleness, Redis availability, MinIO capacity, DB pool pressure, backend SLOs, public-link abuse, JVM heap, and host disk; CI now verifies alert documentation and rule structure. | Alert changes pass `scripts/verify-prometheus-alerts.ps1`, and new runtime metrics still need scrape/unit evidence. |
| Backup reliability | Backups exist, but restore confidence matters more than backup creation. | `deploy/oci/scripts/backup-to-gdrive.sh` now supports optional `age`/`gpg` encryption before upload and uploads `.sha256` sidecars; `docs/backup_restore_rehearsal_runbook.md` and restore docs require checksum, decrypt, smoke-count, cleanup, and exception evidence. | Documented restore rehearsal includes artifact name, checksum result, encryption/decrypt result, smoke counts, cleanup, timestamp, and plaintext exception owner/expiry when needed. |
| Accessibility/mobile UX | Drag widgets, maps, drive, modals, and PIN/auth screens have keyboard and touch risks. | `docs/accessibility_mobile_checklist.md` is now backed by `scripts/verify-accessibility-mobile-checklist.ps1`, which gates WCAG 2.2 traceability, priority-screen risk rows, release evidence fields, PIN/share frontend anchors, and CI release-gate wiring. | Priority screens have keyboard, focus, target-size, drag-alternative, status/error-state, reduced-motion, and 360x640 mobile evidence. |
| CI gates | Manual checks are easy to skip. | Run backend test, frontend build, reusable PowerShell secret scan, secret-scan-contract drift checks, ci-workflow-contract topology checks, and config sync in GitHub Actions. | Push/PR triggers automated backend, frontend, config, high-risk secret gates, scanner contract checks, and release-gate topology checks. |
| E2E smoke coverage | Core user flows need browser-level evidence beyond backend unit/integration tests. | Use `docs/e2e_smoke_checklist.md` plus `frontend/e2e/smoke.spec.js` for login, ledger entry, Excel import, OCR confirm-save, drive share, admin backup, travel upload, AI analysis, and notifications. The first Playwright skeleton now covers session/route checkpoints and fixture-gated P0 flow placeholders. | P0 smoke evidence is attached before releases that change those flows; automated runs must show passing feature-specific assertions or approved skips. |

## P2: Product Expansion

| Feature | User value | First slice | Evidence of done |
| --- | --- | --- | --- |
| AI ledger coach | Turns monthly reports into ongoing guidance. | Add risk spend, recurring spend, budget-overrun forecast fields to AI output. | UI shows forecast and action list without mutating ledger data. |
| Auto classification rules | Improves OCR/Excel import speed and consistency. | User-defined keyword rule CRUD and preview API are in place; next apply rules in OCR/Excel import preview. | Users can create owner-scoped rules and preview explainable classification suggestions. |
| Transaction anomaly detection | Catches duplicate, unusual, and out-of-context spending. | Read-only duplicate detector API now flags same-day expense entries with same amount/title; next add frontend panel and dismiss workflow. | API returns user-scoped anomaly candidates without mutating ledger data. |
| Travel timeline/story export | Combines route, photos, expenses, and memories into a shareable result. | Read-only web story for one trip. | Public/private story link renders route/photos/memories. |
| PWA/mobile capture | OCR, travel photos, and family album are mobile-heavy. | PWA manifest, production service-worker baseline, and camera capture hints for OCR, travel memory, family album, and CalenDrive profile image flows are in place; next add offline draft UX. | Production frontend is installable, API/private data is excluded from service-worker caching, and CI gates capture hints plus app-shell privacy boundaries. |
| Drive file versioning | Makes CalenDrive safer for family use. | Version table, upload-created version rows, owner-scoped version list API, restore endpoint, and CalenDrive frontend version drawer are in place. | Uploading a new file records version 1, users can inspect selected-file versions, restore records a RESTORE version row, and the version API is owner-scoped. |
| Family budget/shared goals | Makes Household area more distinct. | Owner-scoped personal household goal backend API, schema updater, archive mutation, and bounded `GOAL_PROGRESS` notification producer are in place before multi-member sharing. | Next wire Household frontend goal cards and then add explicit membership/grant rows for shared goals. |
| Notification center | Connects disconnected events. | Backend notification APIs now receive AI analysis, OCR failure, scheduled-backup failure, privacy cleanup, shared-file, privacy export, travel-start reminder, travel budget warning, and household goal progress events; frontend route rendering and the topbar unread badge are wired; next wire frontend household goal cards. | Users can list/read own notifications, see an owner-scoped unread badge, and receive AI/OCR/backup/privacy/export/share/travel reminder/budget warning/household goal event records with bounded metadata. |
| Data portability | Builds trust for personal data platform. | Backend data export returns a secondary-PIN-protected ledger CSV plus safe drive/travel/family media manifests and emits bounded `PRIVACY_EXPORT_DONE` notifications; `ProfileWorkspace.vue` exposes the privacy export action with date range, secondary-PIN dialog, manifest-only explanation, live status messages, and stable E2E anchors. | Authenticated user can download own archive without exposing secrets, storage paths, raw coordinates, prompts, provider responses, or other users data; future binary export must remain async, bounded, encrypted, and expiring. |
| Privacy control panel | Gives users control over sensitive records. | Backend slice now supports AI history deletion and public drive link revocation; next add GPS stripping and export UI. | Authenticated user can clean up own derived AI data and revoke own public links with returned counts. |

## Suggested Execution Order

1. Keep `docs/public_share_authorization_contract.md` current while adding frontend owner access-log views and Travel media token expiry tests.
2. Add AI response validator and provider client tests.
3. Keep `docs/file_upload_security_contract.md` current while adding per-feature max-size tests and malformed-image upload tests.
4. Add metrics/alerts for AI/OCR/backup/Redis failures.
5. Follow `docs/service_decomposition_plan.md` to extract `LedgerAiAnalysisService` and `TravelService` collaborators in low-risk slices.
6. Retire the next legacy `*SchemaUpdater` by adding a versioned migration, evidence row, and staging Flyway startup proof.
7. Build AI ledger coach fields on top of the hardened AI contract.
8. Add notification center so AI/backup/share events become visible.
9. Complete fixture-backed Playwright automation for the P0 flows in `frontend/e2e/smoke.spec.js`.

## Current Documentation Set

| Document | Purpose |
| --- | --- |
| `docs/security_baseline_checklist.md` | Security checklist and immediate tests. |
| `docs/remember_me_security_review.md` | Remember-me cookie, persistent token, logout, rotation, and revocation review. |
| `docs/ledger_ai_safety_hardening.md` | AI provider safety, failure handling, and hardening backlog. |
| `docs/ai_provider_safety_contract.md` | Release contract for LM Studio/n8n provider safety, response validation, prompt-injection rejection, payload minimization, duplicate suppression, and secret redaction. |
| `docs/env_configuration_contract.md` | Runtime configuration contract for `.env.example`, `.env.oci.app.example`, `application.yml`, LM Studio/n8n provider endpoints, secret placeholders, and production-safety toggles. |
| `docs/secret_scanning_contract.md` | Secret scanning contract for token patterns, sensitive assignment placeholders, CI jobs, rotation expectations, and release evidence. |
| `docs/ci_workflow_contract.md` | GitHub Actions topology contract for required jobs, release-gate needs/results, glued YAML key detection, and durable CI gate review. |
| `docs/project_improvement_roadmap.md` | Prioritized roadmap for improvements and new features. |
| `docs/db_migration_strategy.md` | Flyway transition plan, current migration inventory, CI migration discipline, and schema updater retirement queue. |
| `docs/service_decomposition_plan.md` | Ledger AI and Travel service extraction order, guardrails, test boundaries, and exit criteria. |
| `docs/accessibility_mobile_checklist.md` | WCAG 2.2 and mobile UX checklist for priority screens. |
| `docs/e2e_smoke_checklist.md` | Core browser smoke flows, Playwright skeleton contract, shared test data, acceptance criteria, and automation conversion notes. |
| `docs/backup_restore_rehearsal_runbook.md` | Restore rehearsal evidence, encryption readiness, and failure-handling checklist. |
| `docs/privacy_control_panel.md` | Backend privacy controls, safety rules, response contract, and next privacy actions. |
| `docs/pwa_mobile_capture.md` | PWA installability, service-worker cache policy, mobile camera capture anchors, and offline draft implementation queue. |
| `docs/data_portability.md` | User data export API, archive contents, safe manifest rules, standard import/export direction, and async binary archive release contract. |
| `docs/drive_file_versioning.md` | CalenDrive version table/API/frontend drawer contract, owner-scoped restore behavior, and replacement-upload backlog. |
| `docs/notification_center.md` | Notification API contract, safety rules, producer queue, and test backlog. |
| `docs/transaction_anomaly_detection.md` | Deterministic duplicate detector API, safety rules, and next anomaly detectors. |
| `docs/ledger_classification_rules.md` | User-defined classification rule API, matching behavior, safety rules, and next import slices. |
| `docs/travel_story_export.md` | Travel story/export visibility, media-token safety, secret exclusion, and async export release contract. |
| `docs/household_budget_goals.md` | Household budget/shared goal owner/member scope, contribution, export, notification, and mutation safety contract. |
## Current Automation Set

| Automation | Purpose |
| --- | --- |
| `.github/workflows/ci.yml` | Runs reusable secret scan, config sync, backend tests, and frontend build on push/PR. |
| `scripts/verify-env-sync.ps1` | Fails when Spring env placeholders drift from checked env examples, required operational groups, `docs/env_configuration_contract.md`, secret placeholder rules, LM Studio endpoint defaults, production AI allowlist defaults, or compose-only allowlist. |
| `scripts/scan-secrets.ps1` | Fails CI on high-risk token patterns or non-placeholder sensitive env assignments. |
| `scripts/verify-secret-scan-contract.ps1` | Fails CI if high-risk secret gates, scanner patterns, placeholder policy, security baseline coverage, roadmap coverage, or release-gate wiring drift. |
| `scripts/verify-ci-workflow-contract.ps1` | Fails CI if required workflow jobs, release-gate dependencies, result-map entries, or CI topology documentation drift. |
| `scripts/verify-ledger-anomaly-contract.ps1` | Fails CI if transaction anomaly detection loses read-only owner scope, bounded range/limit rules, detector evidence, security checklist coverage, or release-gate wiring. |
| `scripts/verify-travel-story-export-contract.ps1` | Fails CI if travel story/export loses owner/shared/public visibility rules, media-token safety, secret exclusion, roadmap coverage, or release-gate wiring. |
| `scripts/verify-household-budget-goals-contract.ps1` | Fails CI if household budget/shared-goal contracts lose owner/member scope, explicit mutation boundaries, export/notification safety, implementation anchors, or release-gate wiring. |
| `scripts/verify-notification-center-contract.ps1` | Fails CI if notification center loses owner scope, redaction, bounded metadata, relative target links, frontend/API anchors, roadmap coverage, or release-gate wiring. |
| `scripts/verify-drive-file-versioning-contract.ps1` | Fails CI if CalenDrive file versioning loses owner-scoped backend anchors, frontend drawer/restore anchors, version metadata safety, roadmap coverage, or release-gate wiring. |
| `scripts/verify-data-portability-contract.ps1` | Fails CI if data portability loses secondary-PIN protection, owner scope, safe manifest/secret exclusion, `ProfileWorkspace.vue` frontend anchors, roadmap coverage, or release-gate wiring. |
| `scripts/verify-pwa-mobile-baseline.ps1` | Fails CI if PWA manifest/app-shell privacy, production-only service worker registration, WCAG/mobile checklist anchors, or camera capture hints for core upload flows drift. |
| `scripts/verify-db-migrations.ps1` | Fails CI on malformed migration names, duplicate versions, missing baseline marker, undocumented migration/evidence rows, unexpected legacy `*SchemaUpdater` files, or new startup DDL runners. |
| `scripts/verify-prometheus-alerts.ps1` | Fails CI when alert rules are malformed, undocumented, or not loaded by Prometheus. |







