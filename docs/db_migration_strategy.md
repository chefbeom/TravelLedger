# DB Migration Strategy

Updated: 2026-06-30

## Current Transition State

- Flyway is available in the backend build with `flyway-core` and `flyway-mysql`.
- Flyway is disabled by default with `DB_MIGRATION_ENABLED=false` while legacy startup updaters still exist.
- Hibernate `ddl-auto: update` remains in place during the transition so existing local and compose workflows keep working.
- `backend/src/main/resources/db/migration` contains a baseline marker plus versioned migrations for access-log, notification, classification-rule, and AI-history provider changes.
- `scripts/verify-db-migrations.ps1` and the CI `migration-discipline` job check migration naming, duplicate versions, baseline marker presence, and the expected legacy `*SchemaUpdater` inventory.

## Current Migration Inventory

| Version | Purpose |
| --- | --- |
| `V20260629_000__baseline_marker.sql` | No-op baseline marker for controlled Flyway adoption. |
| `V20260629_001__drive_download_link_access_logs.sql` | Drive public download access log table. |
| `V20260629_002__user_notifications.sql` | User notification table. |
| `V20260629_003__ledger_classification_rules.sql` | Ledger classification rule table. |
| `V20260629_004__ledger_ai_history_provider.sql` | AI history provider column and query index. |

## Legacy Schema Updater Inventory

| Priority | Class | Retirement target |
| --- | --- | --- |
| 1 | `LedgerAiAnalysisSchemaUpdater` | Capture all AI analysis history columns, provider/model indexes, JSON/text fields, and remove startup mutation. |
| 2 | `LedgerEntrySchemaUpdater` | Capture ledger entry/category/payment schema drift and indexes. |
| 3 | `LedgerEntryChangeHistorySchemaUpdater` | Capture audit/change-history table and indexes. |
| 4 | `TravelMediaAssetSchemaUpdater` | Capture media asset columns, storage path fields, public token fields, and indexes. |
| 5 | `TravelPhotoClusterSchemaUpdater` | Capture map/photo clustering tables and member indexes. |
| 6 | `TravelRouteSchemaUpdater` | Capture route/GPX fields and indexes. |

## Operating Rules

| Rule | Reason |
| --- | --- |
| Add every new schema change as a `VYYYYMMDD_NNN__description.sql` migration. | Gives rollback/audit evidence and makes deploys reproducible. |
| Run `scripts/verify-db-migrations.ps1` before merging schema work. | Catches duplicate versions, filename drift, missing baseline marker, and unexpected legacy startup schema updaters before CI. |
| Do not edit a migration after it has run outside a local throwaway DB. | Flyway checksums should remain stable between environments. |
| Keep Flyway disabled in production until a staging rehearsal passes. | Avoids surprising startup failures while legacy schema is still updater-managed. |
| Convert one schema area at a time and remove the matching `*SchemaUpdater` only after migration evidence exists. | Reduces risk while replacing startup mutation logic. |
| Include a rollback note for every migration. | Production data rollback often means restore/replay rather than SQL down migrations. |

## Enablement Steps

1. Back up the target database.
2. Confirm the app starts with `DB_MIGRATION_ENABLED=false`.
3. Run backend tests and `scripts/verify-db-migrations.ps1` locally.
4. Enable `DB_MIGRATION_ENABLED=true` in a staging copy first.
5. Keep `DB_MIGRATION_BASELINE_ON_MIGRATE=true` for existing non-empty schemas without `flyway_schema_history`.
6. Confirm `flyway_schema_history` exists and contains the baseline marker plus expected migrations.
7. After all legacy schema updaters are converted, set Hibernate DDL mode to `validate` for non-local profiles.

## Release Gate

A release that adds or changes schema should include:

- A new versioned migration under `backend/src/main/resources/db/migration`.
- A passing `scripts/verify-db-migrations.ps1` result or CI `migration-discipline` job, including the legacy updater inventory check.
- A note explaining whether an existing `*SchemaUpdater` was retained, reduced, or removed.
- A rollback note for data-preserving rollback or restore-from-backup rollback.
- A staging startup check with Flyway enabled before production promotion.