# DB Migration Strategy

Updated: 2026-06-29

## Current Transition State

- Flyway is available in the backend build, but disabled by default with `DB_MIGRATION_ENABLED=false`.
- Hibernate `ddl-auto: update` remains in place during the transition so existing local and compose workflows keep working.
- `V20260629_000__baseline_marker.sql` is a no-op marker that establishes the first versioned migration entry.
- Existing `*SchemaUpdater` classes should be retired gradually after their behavior is captured in versioned migrations.

## Operating Rules

| Rule | Reason |
| --- | --- |
| Keep Flyway disabled until a schema rehearsal passes. | Avoids surprising production startup failures while the legacy schema is still updater-managed. |
| Add every new schema change as a versioned migration. | Gives rollback/audit evidence and makes deploys reproducible. |
| Do not edit a migration after it has run outside a local throwaway DB. | Flyway checksums should remain stable between environments. |
| Convert one schema area at a time. | Reduces risk while replacing legacy startup mutation logic. |
| Keep migration scripts idempotent only when explicitly needed for legacy compatibility. | Normal Flyway migrations should be deterministic and versioned, not hidden best-effort updates. |

## Enablement Steps

1. Back up the target database.
2. Confirm the app starts with `DB_MIGRATION_ENABLED=false`.
3. Enable `DB_MIGRATION_ENABLED=true` in a staging copy first.
4. Keep `DB_MIGRATION_BASELINE_ON_MIGRATE=true` for existing non-empty schemas without `flyway_schema_history`.
5. Confirm `flyway_schema_history` exists and contains the baseline marker before promoting the same setting to production.
6. After all legacy schema updaters are converted, set Hibernate DDL mode to `validate` for non-local profiles.

## Schema Updater Retirement Queue

| Priority | Area | Migration target |
| --- | --- | --- |
| 1 | Ledger/AI analysis history | Capture AI report tables, JSON/text columns, and indexes. |
| 2 | Drive and public links | Capture file metadata, share-link, and token/index constraints. |
| 3 | Travel/family/support uploads | Capture attachment metadata and foreign-key assumptions. |
| 4 | Admin/audit/backup tables | Capture audit event fields and operational metadata. |

## Release Gate

A release that adds or changes schema should include:

- A new `VYYYYMMDD_NNN__description.sql` migration.
- A note in the PR or commit explaining whether an existing `*SchemaUpdater` was retained, reduced, or removed.
- A rollback note for data-preserving rollback or restore-from-backup rollback.
- A staging startup check with Flyway enabled before production promotion.