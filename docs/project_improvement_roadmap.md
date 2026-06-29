# Project Improvement Roadmap

Updated: 2026-06-29

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
| Security baseline | Auth, CSRF, admin, sharing, upload, presigned URLs, and AI keys are mixed across domains. | Use `docs/security_baseline_checklist.md` as release checklist and add P0 tests. | Security-focused backend tests pass; public allowlist is reviewed. |
| AI safety | Ledger AI sends sensitive spending data to LM Studio or n8n and stores results. | Add response validator and malformed-output tests. | Provider failures store failed history; invalid JSON/schema is rejected. |
| Admin/share authorization tests | Admin APIs and public links are high-impact. | Add tests for non-admin admin access and invalid/revoked public links. | Normal users receive `403`; invalid tokens receive controlled errors. |
| Upload validation | Drive/travel/family/support/OCR upload paths have different risk profiles. | Define per-feature max size and MIME rules. | Oversized/spoofed uploads are rejected by tests. |
| Configuration sync | `.env.example`, `application.yml`, compose files can drift. | Add a manual checklist now; later add script verification. | New env names are documented in README and examples. |

## P1: Operations and Maintainability

| Workstream | Why it matters | First slice | Evidence of done |
| --- | --- | --- | --- |
| Service decomposition | `LedgerAiAnalysisService` and `TravelService` carry many responsibilities. | Extract AI payload builder, report merger, and response validator. | Smaller classes with focused unit tests. |
| DB migration management | `*SchemaUpdater` classes are convenient but weak for production rollback/audit. | Introduce Flyway or Liquibase for new tables first. | New schema change has versioned migration and repeatable local start. |
| Observability/alerts | Prometheus/Grafana exists, but alerting is the next operational jump. | Add metrics for AI/OCR failures, backup failures, Redis errors. | Grafana/Prometheus alert rules documented and tested by metric scrape. |
| Backup reliability | Backups exist, but restore confidence matters more than backup creation. | Add restore rehearsal checklist and encrypted artifact option. | Documented restore test with timestamped result. |
| CI gates | Manual checks are easy to skip. | Add backend test, frontend build, and secret scan to Jenkins/GitHub Actions. | Push triggers automated gates. |

## P2: Product Expansion

| Feature | User value | First slice | Evidence of done |
| --- | --- | --- | --- |
| AI ledger coach | Turns monthly reports into ongoing guidance. | Add risk spend, recurring spend, budget-overrun forecast fields to AI output. | UI shows forecast and action list without mutating ledger data. |
| Auto classification rules | Improves OCR/Excel import speed and consistency. | User-defined merchant/title keyword rules with preview. | Import preview applies explainable rules before save. |
| Transaction anomaly detection | Catches duplicate, unusual, and out-of-context spending. | Deterministic detector for duplicate same-day/same-amount entries. | Anomaly panel flags examples with dismiss action. |
| Travel timeline/story export | Combines route, photos, expenses, and memories into a shareable result. | Read-only web story for one trip. | Public/private story link renders route/photos/memories. |
| PWA/mobile capture | OCR, travel photos, and family album are mobile-heavy. | PWA manifest, camera upload affordance, offline draft storage. | Mobile install works and camera upload path is tested. |
| Drive file versioning | Makes CalenDrive safer for family use. | Version table and restore previous file version. | Uploading same file can preserve previous version. |
| Family budget/shared goals | Makes Household area more distinct. | Shared monthly budget and goal progress widget. | Household dashboard shows goal/budget status. |
| Notification center | Connects disconnected events. | Store/read notifications for AI done, budget warning, backup failure, shared file. | Header badge and notification list work across events. |
| Data portability | Builds trust for personal data platform. | User export archive for ledger CSV and metadata JSON. | User can request/download own export. |
| Privacy control panel | Gives users control over sensitive records. | Revoke all share links, delete AI history, strip photo GPS metadata. | User-facing privacy page performs each action with confirmation. |

## Suggested Execution Order

1. Add P0 backend tests: admin access, public link denial, AI malformed output.
2. Add AI response validator and provider client tests.
3. Add upload validation matrix and tests per upload family.
4. Add metrics/alerts for AI/OCR/backup/Redis failures.
5. Extract `LedgerAiAnalysisService` into smaller collaborators.
6. Introduce migration tooling for new schema changes.
7. Build AI ledger coach fields on top of the hardened AI contract.
8. Add notification center so AI/backup/share events become visible.

## Current Documentation Set

| Document | Purpose |
| --- | --- |
| `docs/security_baseline_checklist.md` | Security checklist and immediate tests. |
| `docs/ledger_ai_safety_hardening.md` | AI provider safety, failure handling, and hardening backlog. |
| `docs/project_improvement_roadmap.md` | Prioritized roadmap for improvements and new features. |