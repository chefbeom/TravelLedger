# Data Portability Contract

Updated: 2026-06-30

This document is the release contract for user data portability. The current backend export provides a password-protected archive containing ledger CSV data, export metadata, and safe file/media manifests. The long-term product direction is user-owned full data export, async photo/file archive packaging, and standardized ledger CSV/Excel import/export that makes TravelLedger trustworthy as a personal data platform. The archive is created only for the authenticated user and only after a recently verified secondary PIN session. Successful exports create a bounded `PRIVACY_EXPORT_DONE` notification without file names, archive contents, secondary PIN values, tokens, or storage paths.

## Implemented API

| Endpoint | Method | Purpose | Protection |
| --- | --- | --- | --- |
| `/api/privacy/data-export` | `POST` | Downloads a user data archive for the authenticated user. | Authentication, CSRF, secondary PIN session, and password-protected ZIP. |

Request body is optional:

```json
{
  "from": "2026-01-01",
  "to": "2026-12-31"
}
```

When `from` and `to` are omitted, all non-deleted ledger entries visible to the authenticated owner are exported.

## Portability product tiers

| Tier | User value | First safe release | Guardrail |
| --- | --- | --- | --- |
| Account data export | User can download their own ledger, profile-adjacent metadata, and safe drive/travel/family manifests. | Current password-protected archive with ledger CSV, export metadata, and manifest-only media/file listings. | Owner scope, CSRF, secondary PIN, secret exclusion, safe manifests, and bounded completion notification remain mandatory. |
| Photo/file archive | User can request an archive that includes original photos, family album media, and CalenDrive files. | Future async binary archive job with queueing, progress, size limits, resumable/retry policy, encryption, expiration cleanup, and restore rehearsal evidence. | Never run synchronously in the request thread; never include another user's private files; never expose storage paths, public tokens, or presigned URLs. |
| Standard ledger export | User can move ledger data to external tools. | Stable CSV/Excel schema with documented columns, time zone, currency, amount, category, payment method, tags, and import report metadata. | Exported rows stay owner-scoped and omit internal ids/secrets unless explicitly documented as portable external ids. |
| Standard ledger import | User can bring ledger data back from CSV/Excel without corruption. | Preview-first import with row validation, duplicate detection, category/payment-method mapping, conflict report, and explicit confirm-save. | Import cannot write until validation passes and the user confirms; every row is assigned to the authenticated owner only. |

## Full archive release requirements

| Requirement | Required behavior |
| --- | --- |
| Async job | Binary photo/file archive generation must run through a queued job with progress API, cancel/expire states, retry budget, and cleanup ownership. |
| Size and type bounds | Enforce per-file, total-byte, item-count, and archive-count limits before reading object storage data. |
| Encryption and expiry | Archive output must be encrypted, time-limited, and deleted after expiry or successful cleanup. |
| Access isolation | Archive workers must re-check owner/member visibility before adding every ledger row, drive file, travel photo, or family album item. |
| Restore rehearsal | Each release must prove the archive can be opened and interpreted without secrets, object storage paths, or presigned URLs. |
| Audit/notification | Completion/failure notifications must contain only status, date range, archive scope, counts, and relative target links. |

## CSV/Excel standardization contract

| Area | Export rule | Import rule |
| --- | --- | --- |
| Columns | Publish stable column names for date, type, amount, currency, KRW amount, category, payment method, memo, tags, travel link, and external id. | Reject unknown required columns, tolerate optional columns, and report unmapped values before writes. |
| Dates/time zones | Export dates with documented locale/time-zone behavior. | Parse only supported date formats and show row-level errors for ambiguous values. |
| Amounts | Export signed/typed amounts consistently for income, expense, transfer, and travel-linked records. | Validate numeric range, currency, sign, and record type before preview confirmation. |
| Classification | Export category/payment method labels plus portable external ids when safe. | Map by owner-visible labels/rules and let the user approve new categories or payment methods. |
| Idempotency | Include optional import batch id and row fingerprint in reports. | Detect duplicate rows and repeated imports before saving. |
| Report | Include manifest metadata and row counts. | Produce accepted/rejected/conflict counts with downloadable error report. |
## Export flow
```mermaid
flowchart TD
    A["Authenticated user"] --> B["POST /api/privacy/data-export"]
    B --> C["CSRF and session checks"]
    C --> D["Read verified secondary PIN"]
    D --> E["Export owner-scoped ledger CSV"]
    E --> F["Build safe drive/travel/family manifests"]
    F --> G["Create password-protected archive"]
    G --> H["Download application/zip"]
```

## Archive contents

| Path | Description | Privacy boundary |
| --- | --- | --- |
| `ledger/*.csv` | Ledger CSV generated by the existing ledger export formatter. | Owner-scoped through the ledger export service. |
| `metadata/export-metadata.json` | Export timestamp, owner identity, requested date range, included files, and safe counts. | Contains counts and descriptors, not operational secrets. |
| `manifest/drive-items.json` | Safe CalenDrive item manifest. | Excludes object storage paths, public URLs, presigned URLs, and raw share credentials. |
| `manifest/travel-media.json` | Safe travel media manifest. | Exposes `hasGpsMetadata` and timestamps, not raw latitude/longitude coordinates. |
| `manifest/family-media.json` | Safe family media manifest. | Excludes storage paths and temporary access URLs. |

Binary photos and files are intentionally not included in the current archive. They require an async job with progress, size limits, retry behavior, expiration cleanup, and restore rehearsal evidence before release.

## Non-negotiable safety rules

| Rule | Required behavior |
| --- | --- |
| Owner scope | The request body cannot target another user; the controller uses `@AuthenticationPrincipal` and passes only the current user id. |
| Unsafe method protection | `POST /api/privacy/data-export` remains authenticated and CSRF-protected. |
| Secondary PIN | Export requires a recently verified secondary PIN session, and the password-protected archive uses that verified secondary PIN. |
| Secret exclusion | Archives must not include API keys, access tokens, backup credentials, workflow URLs, prompt payloads, provider responses, model responses, object storage paths, signed URLs, presigned URLs, public link tokens, raw share credentials, or secondary PIN values. |
| Location privacy | Travel media manifests may expose `hasGpsMetadata`; they must not expose raw latitude/longitude or raw EXIF/GPS payloads. |
| Membership privacy | Future household, family, travel, and shared-budget exports must include only data visible to the current user. |
| Binary archive boundary | Binary file/photo export must be async, bounded, encrypted, expiring, and separately rehearsed before it can be part of the release path. |
| Import/export standardization | Future standard CSV/Excel import and export schemas must preserve owner scope, manifest redaction, preview-first validation, duplicate detection, row-level error reports, and explicit confirm-save before database writes. |
| Export notification | Completion notifications must store only status, date range label, archive scope, counts, and relative target links; they must not include file names, archive contents, secondary PIN values, tokens, public links, presigned URLs, storage paths, prompts, provider responses, raw GPS, or owner identity fields. |
| Import is preview-first | CSV/Excel import must produce validation and conflict reports before any ledger write; saving requires explicit user confirmation. |

## Current implementation anchors

| Area | Anchor |
| --- | --- |
| API controller | `PrivacyController.exportUserDataArchive` maps `POST /api/privacy/data-export`, reads `@AuthenticationPrincipal AppUserPrincipal currentUser`, verifies the secondary PIN session, and returns `application/zip` as an attachment. |
| Export service | `DataPortabilityExportService.exportUserDataArchive` verifies the secondary PIN, exports ledger CSV, fetches owner-scoped drive/travel/family manifest data, creates the encrypted ZIP, and emits `PRIVACY_EXPORT_DONE` with bounded metadata. |
| Manifest safety | `buildDriveManifest`, `buildTravelMediaManifest`, `buildFamilyMediaManifest`, and `excludedFields` document the safe manifest boundary. |
| Service tests | `DataPortabilityExportServiceTest.exportUserDataArchiveBuildsEncryptedArchiveWithoutOperationalSecrets` checks encrypted archive contents, secret exclusion, and the bounded privacy-export notification. |
| Ordering tests | `DataPortabilityExportServiceTest.exportUserDataArchiveVerifiesSecondaryPinBeforeExportingLedgerData` keeps secondary PIN verification before ledger export. |
| API tests | PrivacyControllerIntegrationTest.dataExportRequiresAuthenticationCsrfAndVerifiedSecondaryPin keeps auth, CSRF, secondary PIN, and ZIP response behavior covered. |
| Frontend privacy action | `frontend/src/components/ProfileWorkspace.vue` exposes the privacy panel, date range fields, secondary-PIN export dialog, manifest-only archive explanation, live status messages, and stable data-testid anchors such as privacy-data-export-card, privacy-export-open, and privacy-export-secondary-pin. |

## Release gate

The `data-portability-contract` CI job must pass before promoting changes that affect privacy controls, data export, ledger CSV generation, media manifests, storage links, secondary PIN handling, export notifications, or future import/export jobs.

A release is not ready if any of these are true:

| Failure | Why it blocks release |
| --- | --- |
| Export can be requested for another user. | Breaks owner scope. |
| Export does not require authentication, CSRF, or secondary PIN. | Creates direct account-data exfiltration risk. |
| Archive or export notification includes operational secrets, storage paths, public tokens, presigned URLs, raw GPS, AI prompts, provider responses, file names, archive contents, or secondary PIN values. | Leaks infrastructure and sensitive derived data. |
| Binary media export is synchronous or unbounded. | Creates timeout, memory, cost, and partial-export risk. |
| Photo/file archive lacks encryption, expiry, progress, cleanup, or restore rehearsal evidence. | Makes full-data export unreliable and risky for private media/files. |
| Standard CSV/Excel import bypasses validation, preview, duplicate detection, explicit confirmation, or owner scope. | Can corrupt or cross-contaminate user data. |

## CI contract

`scripts/verify-data-portability-contract.ps1` keeps this document synchronized with `PrivacyController`, `DataPortabilityExportService`, `frontend/src/components/ProfileWorkspace.vue`, data portability tests, `docs/security_baseline_checklist.md`, `docs/project_improvement_roadmap.md`, and the GitHub Actions `data-portability-contract` job.

## Next slices

| Slice | Notes |
| --- | --- |
| Async binary archive job | Add queueing, progress API, size limits, retry policy, archive encryption, archive expiration, cleanup ownership, and restore rehearsal evidence before including photos/files. |
| Standard CSV/Excel export schema | Define stable column names, time zone rules, currency/amount formats, category/payment-method mapping, portable external ids, and manifest metadata for external tools. |
| Standard CSV/Excel import schema | Validate rows before writes, preview conflicts, detect duplicates/repeated imports, preserve owner scope, and produce an import/error report before explicit confirm-save. |
| Frontend E2E coverage | Drive the `ProfileWorkspace.vue` privacy panel through Playwright once disposable privacy/export fixtures are available: date range, secondary PIN dialog, manifest-only limitation, download success, and failure live regions. |
| Restore/rehearsal runbook | Prove exported data can be interpreted safely without secrets or object storage internals. |

## Test backlog

- Export rejects unauthenticated requests.
- Export rejects unsafe requests without CSRF.
- Export rejects requests without a verified secondary PIN session.
- Export includes only the authenticated user's ledger entries and visible media manifest rows.
- Export date range filters ledger CSV rows.
- Export metadata and `PRIVACY_EXPORT_DONE` notification metadata contain no secrets, signed URLs, presigned URLs, public tokens, raw GPS, prompts, provider responses, file names, archive contents, secondary PIN values, or storage internals.
- Async binary archive job enforces size limits, expiration, retry behavior, and encrypted output before release.
- Standard CSV/Excel import/export preserves owner scope, validates every row before writes, detects duplicates, produces row-level reports, and requires explicit confirm-save.

