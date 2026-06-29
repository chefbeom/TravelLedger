# DB Migration Strategy

Updated: 2026-06-30

## Current Transition State

- Flyway is available in the backend build with `flyway-core` and `flyway-mysql`.
- Flyway is disabled by default with `DB_MIGRATION_ENABLED=false` while legacy startup updaters still exist.
- Hibernate `ddl-auto: update` remains in place during the transition so existing local and compose workflows keep working.
- `backend/src/main/resources/db/migration` contains a baseline marker plus versioned migrations for access logs, notifications, classification rules, AI history provider metadata, drive file versions, drive share permissions, and direct-share access log indexes.
- `scripts/verify-db-migrations.ps1` and the CI `migration-discipline` job check migration naming, duplicate versions, baseline marker presence, migration inventory documentation, operational evidence notes, and the expected legacy `*SchemaUpdater` inventory.

## Current Migration Inventory

| Version | Purpose |
| --- | --- |
| `V20260629_000__baseline_marker.sql` | No-op baseline marker for controlled Flyway adoption. |
| `V20260629_001__drive_download_link_access_logs.sql` | Drive public download access log table. |
| `V20260629_002__user_notifications.sql` | User notification table. |
| `V20260629_003__ledger_classification_rules.sql` | Ledger classification rule table. |
| `V20260629_004__ledger_ai_history_provider.sql` | AI history provider column and query index. |
| `V20260629_005__drive_item_versions.sql` | CalenDrive file version ledger table and owner/item indexes. |
| `V20260629_006__drive_share_permissions.sql` | Drive share permission level columns and supporting indexes. |
| `V20260629_007__drive_direct_share_access_log_index.sql` | Direct drive share access-log status and lookup indexes. |

## Legacy Schema Updater Inventory

| Priority | Class | Retirement target |
| --- | --- | --- |
| 1 | `LedgerAiAnalysisSchemaUpdater` | Capture all AI analysis history columns, provider/model indexes, JSON/text fields, and remove startup mutation. |
| 2 | `LedgerEntrySchemaUpdater` | Capture ledger entry/category/payment schema drift and indexes. |
| 3 | `LedgerEntryChangeHistorySchemaUpdater` | Capture audit/change-history table and indexes. |
| 4 | `TravelMediaAssetSchemaUpdater` | Capture media asset columns, storage path fields, public token fields, and indexes. |
| 5 | `TravelPhotoClusterSchemaUpdater` | Capture map/photo clustering tables and member indexes. |
| 6 | `TravelRouteSchemaUpdater` | Capture route/GPX fields and indexes. |

## Migration Operational Evidence

| Migration | Rehearsal scope | Rollback or restore note | Legacy updater impact |
| --- | --- | --- | --- |
| `V20260629_000__baseline_marker.sql` | Existing non-empty DB starts with Flyway baseline enabled in staging. | Disable Flyway and restore the pre-rehearsal DB backup if baseline metadata blocks startup. | No updater retired. |
| `V20260629_001__drive_download_link_access_logs.sql` | Public link download creates and queries access-log rows. | Export logs if needed, then restore the DB backup or drop the new log table in a controlled maintenance window. | New Flyway-managed table. |
| `V20260629_002__user_notifications.sql` | Notification creation, listing, and read-state updates run after migration. | Restore the DB backup if notification state must be preserved; otherwise drop the notification table after draining pending alerts. | New Flyway-managed table. |
| `V20260629_003__ledger_classification_rules.sql` | Rule create/update/delete and OCR/import rule lookup run after migration. | Export user rules before rollback and restore from backup if rule state must be preserved. | New Flyway-managed table. |
| `V20260629_004__ledger_ai_history_provider.sql` | AI analysis history save/list/delete covers provider/model queries. | Restore from backup if provider metadata is required; dropping the column loses provider attribution. | Partially overlaps `LedgerAiAnalysisSchemaUpdater`; updater remains until all AI history columns are migrated. |
| `V20260629_005__drive_item_versions.sql` | File overwrite creates version rows and download/restore reads the expected version. | Restore from backup if versions must be preserved; table drop is only safe after confirming no version history is needed. | New Flyway-managed table. |
| `V20260629_006__drive_share_permissions.sql` | Direct share create/update/read enforces view/download/edit levels. | Restore from backup before downgrading; defaulting permissions during rollback can over-grant access. | New Flyway-managed columns/indexes for drive shares. |
| `V20260629_007__drive_direct_share_access_log_index.sql` | Direct share download audit queries use status and share/user lookup indexes. | Index rollback is low data-risk, but restore backup if paired with access-log schema changes. | Tightens Flyway-managed access-log performance. |

## Operating Rules

| Rule | Reason |
| --- | --- |
| Add every new schema change as a `VYYYYMMDD_NNN__description.sql` migration. | Gives rollback/audit evidence and makes deploys reproducible. |
| Update the Current Migration Inventory and Migration Operational Evidence tables for every tracked migration. | Keeps review and CI focused on deploy rehearsal, rollback, and legacy updater impact instead of filename checks only. |
| Run `scripts/verify-db-migrations.ps1` before merging schema work. | Catches duplicate versions, filename drift, missing baseline marker, undocumented migrations, missing operational evidence, and unexpected legacy startup schema updaters before CI. |
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
- Updated Current Migration Inventory and Migration Operational Evidence rows.
- A passing `scripts/verify-db-migrations.ps1` result or CI `migration-discipline` job, including the legacy updater inventory check.
- A note explaining whether an existing `*SchemaUpdater` was retained, reduced, or removed.
- A rollback note for data-preserving rollback or restore-from-backup rollback.
- A staging startup check with Flyway enabled before production promotion.

## Latest schema slices

- `V20260629_005__drive_item_versions.sql` adds the CalenDrive file version ledger table and owner/item indexes.
- `V20260629_006__drive_share_permissions.sql` adds explicit direct-share permission levels.
- `V20260629_007__drive_direct_share_access_log_index.sql` adds direct-share access-log lookup indexes.
- The legacy `*SchemaUpdater` inventory is unchanged; these are explicit Flyway-managed schema areas.