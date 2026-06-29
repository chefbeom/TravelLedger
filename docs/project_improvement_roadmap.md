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
| Security baseline | Auth, CSRF, admin, sharing, upload, presigned URLs, and AI keys are mixed across domains. | Use `docs/security_baseline_checklist.md` as release checklist, add P0 tests, and keep presigned object-key scope tests current. | Security-focused backend tests cover admin/share/presigned guardrails when run; public allowlist is reviewed. |
| AI safety | Ledger AI sends sensitive spending data to LM Studio or n8n and stores results. | Provider response validation, LM Studio prompt hardening, external workflow metrics, provider payload minimization, 5-minute duplicate suppression, provider URL allowlist controls, status-secret redaction tests, and allowlist/duplicate-suppression/payload contract tests are in place; next add client idempotency keys. | Provider failures store failed history; invalid JSON/schema is rejected, provider failure/latency can be alerted, provider-facing entry data is capped/truncated, quick retries reuse completed results, AI status hides secrets/URLs, and production can restrict AI provider hosts. |
| Admin/share authorization tests | Admin APIs and public links are high-impact. | Drive public download link tests cover invalid/revoked access logging and raw-token-free log storage; travel public media token tests cover invalid token/media/secret pairs; profile credential tests cover missing-CSRF denial for PIN verification/password/PIN changes; account and drive admin mutation tests now cover role, recent verification, and CSRF enforcement. | Normal users receive `403`; invalid public/share tokens are rejected; public link attempts write owner-scoped fingerprint logs; verified admins and profile credential mutations need CSRF. |
| Upload validation | Drive/travel/family/support/OCR upload paths have different risk profiles. | OCR now rejects oversized files, MIME/extension mismatches, and fake image signatures before remote calls; next define the remaining per-feature max size and MIME rules. | OCR oversized/spoofed uploads are rejected by tests; remaining upload families need the same matrix. |
| Configuration sync | `.env.example`, `application.yml`, compose files can drift. | Run `scripts/verify-env-sync.ps1` in CI to compare Spring env placeholders with `.env.example`. | CI fails on missing, duplicate, malformed, or unallowlisted env names. |

## P1: Operations and Maintainability

| Workstream | Why it matters | First slice | Evidence of done |
| --- | --- | --- | --- |
| Service decomposition | `LedgerAiAnalysisService` is 1255 lines and `TravelService` is 3278 lines, so risky behavior is hard to isolate. | Use `docs/service_decomposition_plan.md` to extract pure AI payload/report/plan collaborators first, then travel media/map/share collaborators. | Each extraction leaves controller DTOs stable and adds focused tests for owner scope, AI safety, cache invalidation, and side effects. |
| DB migration management | `*SchemaUpdater` classes are convenient but weak for production rollback/audit. | Flyway is wired behind `DB_MIGRATION_ENABLED`; AI history provider tracking uses a versioned migration; CI now checks migration naming, duplicate versions, and baseline marker presence. | New schema change has a versioned migration, passes migration discipline check, and has repeatable local/staging startup evidence. |
| Observability/alerts | Prometheus/Grafana exists, but alerting is the next operational jump. | Prometheus alert rules cover AI/OCR failures, external workflow latency, backup failures/staleness, Redis availability, MinIO capacity, DB pool pressure, backend SLOs, public-link abuse, JVM heap, and host disk; CI now verifies alert documentation and rule structure. | Alert changes pass `scripts/verify-prometheus-alerts.ps1`, and new runtime metrics still need scrape/unit evidence. |
| Backup reliability | Backups exist, but restore confidence matters more than backup creation. | Use `docs/backup_restore_rehearsal_runbook.md` to record restore evidence and encryption readiness. | Documented restore rehearsal with artifact name, smoke counts, cleanup, and timestamp. |
| Accessibility/mobile UX | Drag widgets, maps, drive, modals, and PIN/auth screens have keyboard and touch risks. | Use `docs/accessibility_mobile_checklist.md` as the WCAG 2.2 release checklist. | Priority screens have keyboard, focus, target-size, and error-state evidence. |
| CI gates | Manual checks are easy to skip. | Run backend test, frontend build, reusable PowerShell secret scan, and config sync in GitHub Actions. | Push/PR triggers automated backend, frontend, config, and high-risk secret gates. |

## P2: Product Expansion

| Feature | User value | First slice | Evidence of done |
| --- | --- | --- | --- |
| AI ledger coach | Turns monthly reports into ongoing guidance. | Add risk spend, recurring spend, budget-overrun forecast fields to AI output. | UI shows forecast and action list without mutating ledger data. |
| Auto classification rules | Improves OCR/Excel import speed and consistency. | User-defined keyword rule CRUD and preview API are in place; next apply rules in OCR/Excel import preview. | Users can create owner-scoped rules and preview explainable classification suggestions. |
| Transaction anomaly detection | Catches duplicate, unusual, and out-of-context spending. | Read-only duplicate detector API now flags same-day expense entries with same amount/title; next add frontend panel and dismiss workflow. | API returns user-scoped anomaly candidates without mutating ledger data. |
| Travel timeline/story export | Combines route, photos, expenses, and memories into a shareable result. | Read-only web story for one trip. | Public/private story link renders route/photos/memories. |
| PWA/mobile capture | OCR, travel photos, and family album are mobile-heavy. | PWA manifest and production service-worker baseline are in place; next add shared camera upload and offline draft UX. | Production frontend is installable and API/private data is excluded from service-worker caching. |
| Drive file versioning | Makes CalenDrive safer for family use. | Version table and restore previous file version. | Uploading same file can preserve previous version. |
| Family budget/shared goals | Makes Household area more distinct. | Shared monthly budget and goal progress widget. | Household dashboard shows goal/budget status. |
| Notification center | Connects disconnected events. | Backend notification APIs now receive AI analysis and shared-file events; next wire backup/budget/travel/OCR producers and frontend badge. | Users can list/read own notifications and receive initial AI/share event records. |
| Data portability | Builds trust for personal data platform. | Backend data export now returns a secondary-PIN-protected ledger CSV plus metadata archive. | Authenticated user can download own archive without exposing secrets or other users data. |
| Privacy control panel | Gives users control over sensitive records. | Backend slice now supports AI history deletion and public drive link revocation; next add GPS stripping and export UI. | Authenticated user can clean up own derived AI data and revoke own public links with returned counts. |

## Suggested Execution Order

1. Add P0 backend tests: admin access, public link denial, AI malformed output.
2. Add AI response validator and provider client tests.
3. Add upload validation matrix and tests per upload family.
4. Add metrics/alerts for AI/OCR/backup/Redis failures.
5. Follow `docs/service_decomposition_plan.md` to extract `LedgerAiAnalysisService` and `TravelService` collaborators in low-risk slices.
6. Introduce migration tooling for new schema changes.
7. Build AI ledger coach fields on top of the hardened AI contract.
8. Add notification center so AI/backup/share events become visible.

## Current Documentation Set

| Document | Purpose |
| --- | --- |
| `docs/security_baseline_checklist.md` | Security checklist and immediate tests. |
| `docs/ledger_ai_safety_hardening.md` | AI provider safety, failure handling, and hardening backlog. |
| `docs/project_improvement_roadmap.md` | Prioritized roadmap for improvements and new features. |
| `docs/db_migration_strategy.md` | Flyway transition plan, current migration inventory, CI migration discipline, and schema updater retirement queue. |
| `docs/service_decomposition_plan.md` | Ledger AI and Travel service extraction order, guardrails, test boundaries, and exit criteria. |
| `docs/accessibility_mobile_checklist.md` | WCAG 2.2 and mobile UX checklist for priority screens. |
| `docs/backup_restore_rehearsal_runbook.md` | Restore rehearsal evidence, encryption readiness, and failure-handling checklist. |
| `docs/privacy_control_panel.md` | Backend privacy controls, safety rules, response contract, and next privacy actions. |
| `docs/pwa_mobile_capture.md` | PWA installability, service-worker cache policy, and mobile capture implementation queue. |
| `docs/data_portability.md` | User data export API, archive contents, security rules, and next portability slices. |
| `docs/notification_center.md` | Notification API contract, safety rules, producer queue, and test backlog. |
| `docs/transaction_anomaly_detection.md` | Deterministic duplicate detector API, safety rules, and next anomaly detectors. |
| `docs/ledger_classification_rules.md` | User-defined classification rule API, matching behavior, safety rules, and next import slices. |
## Current Automation Set

| Automation | Purpose |
| --- | --- |
| `.github/workflows/ci.yml` | Runs reusable secret scan, config sync, backend tests, and frontend build on push/PR. |
| `scripts/verify-env-sync.ps1` | Fails when Spring env placeholders drift from `.env.example` or compose-only allowlist. |
| `scripts/scan-secrets.ps1` | Fails CI on high-risk token patterns or non-placeholder sensitive env assignments. |
| `scripts/verify-db-migrations.ps1` | Fails CI on malformed migration names, duplicate versions, or missing baseline marker. |
| `scripts/verify-prometheus-alerts.ps1` | Fails CI when alert rules are malformed, undocumented, or not loaded by Prometheus. |
