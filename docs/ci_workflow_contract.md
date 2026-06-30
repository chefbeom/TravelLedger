# CI Workflow Contract

Updated: 2026-06-30

This contract keeps the GitHub Actions workflow reviewable as security, migration, AI, upload, privacy, and product-contract gates grow. It is intentionally text-based so it can run before backend or frontend dependencies are installed.

## Required topology

| Area | Required job |
| --- | --- |
| Secret hygiene | `secret-scan`, `secret-scan-contract` |
| CI workflow integrity | `ci-workflow-contract` |
| Runtime/config/schema | `config-sync`, `migration-discipline`, `backup-rehearsal-runbook` |
| Security baseline | `security-baseline-checklist`, `backend-security-tests` |
| Admin/share/upload/privacy | `admin-audit-contract`, `public-share-authorization-contract`, `file-upload-security-contract`, `privacy-control-contract` |
| AI/ledger product safety | `ai-provider-safety-contract`, `ledger-ai-coach-contract`, `ledger-classification-contract`, `ledger-anomaly-contract` |
| Travel/household/notifications/data | `travel-story-export-contract`, `household-budget-goals-contract`, `notification-center-contract`, `drive-file-versioning-contract`, `data-portability-contract` |
| UX/mobile/frontend | `pwa-mobile-baseline`, `accessibility-mobile-checklist`, `frontend-build`, `frontend-e2e-smoke-checklist` |
| Observability/maintainability | `observability-alerts`, `service-decomposition-plan`, `backend-test` |
| Final gate | `release-gate` |

## Required invariants

1. Every required job appears as a top-level job key in `.github/workflows/ci.yml`.
2. `release-gate` lists every required non-release job in `needs`.
3. `release-gate` records every required job result in the `results` map.
4. Workflow lines must not glue two YAML keys together, for example `run: ./script.ps1  next-job:`.
5. Contract/verifier documents must list `ci-workflow-contract` so CI topology drift is visible in reviews.
6. New security-sensitive contract jobs must be added to this document, `scripts/verify-ci-workflow-contract.ps1`, and `release-gate` in the same change.

## Release evidence

A release that changes `.github/workflows/ci.yml`, CI scripts, release gates, security contracts, migration gates, upload gates, AI gates, or frontend build/test gates should include:

- A passing `ci-workflow-contract` result or local `scripts/verify-ci-workflow-contract.ps1` run.
- Confirmation that `release-gate` still depends on every required job.
- Confirmation that new jobs are documented in this contract and in `docs/project_improvement_roadmap.md` when they represent a durable automation gate.