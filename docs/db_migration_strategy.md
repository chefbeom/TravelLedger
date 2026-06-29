# DB Migration Strategy

Updated: 2026-06-30

## Current Transition State

- Flyway is available in the backend build with `flyway-core` and `flyway-mysql`.
- Flyway is disabled by default with `DB_MIGRATION_ENABLED=false` while legacy startup updaters still exist.
- Hibernate `ddl-auto: update` remains in place during the transition so existing local and compose workflows keep working.
- `backend/src/main/resources/db/migration` contains a baseline marker plus versioned migrations for access logs, notifications, classification rules, AI history provider metadata, drive file versions, drive share permissions, direct-share access log indexes, travel route segment fields, ledger entry change-history fields, travel media asset metadata fields, travel photo cluster tables, ledger entry operational fields/indexes, and AI analysis history base table/indexes.
- `scripts/verify-db-migrations.ps1` and the CI `migration-discipline` job check migration naming, duplicate versions, baseline marker presence, migration inventory documentation, operational evidence notes, the expected legacy `*SchemaUpdater` inventory, and unexpected startup DDL runners.

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
| `V20260630_008__travel_route_segment_fields.sql` | Travel route segment path/style/GPX fields previously enforced by startup DDL. |
| `V20260630_009__ledger_entry_change_history_fields.sql` | Ledger entry change-history summary and JSON/text fields previously enforced by startup DDL. |
| `V20260630_010__travel_media_asset_metadata_fields.sql` | Travel media GPS, representative override, GPS extraction timestamp, and lookup indexes previously enforced by startup DDL. |
| `V20260630_011__travel_photo_cluster_tables.sql` | Travel photo cluster and cluster-member tables plus lookup/uniqueness indexes previously enforced by startup DDL. |
| `V20260630_012__ledger_entry_operational_fields.sql` | Ledger entry foreign-currency/travel-link fields and ledger/category/payment lookup indexes previously enforced by startup DDL. |
| `V20260630_013__ledger_ai_analysis_history_base.sql` | Ledger AI analysis history base table, provider column compatibility, and owner/range/mode/provider indexes previously enforced by startup DDL plus provider migration. |

## Legacy Schema Updater Inventory

| Priority | Class | Retirement target |
| --- | --- | --- |
| 1 | `LedgerAiAnalysisSchemaUpdater` | Flyway overlap added in `V20260630_013__ledger_ai_analysis_history_base.sql`; retire after staging Flyway startup proof, AI history save/list/delete smoke evidence, and provider migration ordering rehearsal. |
| 2 | `LedgerEntrySchemaUpdater` | Flyway overlap added in `V20260630_012__ledger_entry_operational_fields.sql`; retire after staging Flyway startup proof and ledger create/search/import smoke evidence. |
| 3 | `LedgerEntryChangeHistorySchemaUpdater` | Flyway overlap added in `V20260630_009__ledger_entry_change_history_fields.sql`; retire after staging Flyway startup proof and restore-history smoke evidence. |
| 4 | `TravelMediaAssetSchemaUpdater` | Flyway overlap added in `V20260630_010__travel_media_asset_metadata_fields.sql`; retire after staging Flyway startup proof and travel media upload/map smoke evidence. |
| 5 | `TravelPhotoClusterSchemaUpdater` | Flyway overlap added in `V20260630_011__travel_photo_cluster_tables.sql`; retire after staging Flyway startup proof and map cluster smoke evidence. |
| 6 | `TravelRouteSchemaUpdater` | Flyway overlap added in `V20260630_008__travel_route_segment_fields.sql`; retire after staging Flyway startup proof and route/GPX smoke evidence. |


## Startup DDL Freeze

No new `ApplicationRunner` or `CommandLineRunner` may execute `CREATE TABLE`, `ALTER TABLE`, `DROP TABLE`, `CREATE INDEX`, or `ALTER INDEX`. New schema changes must be Flyway migrations only. The existing startup DDL classes below are temporary legacy exceptions and are allowed only while their matching migration evidence is prepared.

| Legacy exception | Allowed reason | Retirement evidence required |
| --- | --- | --- |
| `backend/src/main/java/com/playdata/calen/ledger/config/LedgerAiAnalysisSchemaUpdater.java` | Existing AI history table/index bootstrap. | `V20260630_013__ledger_ai_analysis_history_base.sql` now covers the schema; deletion still requires staging Flyway startup proof, AI history save/list/delete smoke evidence, and provider migration ordering rehearsal. |
| `backend/src/main/java/com/playdata/calen/ledger/config/LedgerEntrySchemaUpdater.java` | Existing ledger entry currency, travel-link, category, payment, and search indexes. | `V20260630_012__ledger_entry_operational_fields.sql` now covers the schema; deletion still requires staging Flyway startup proof and ledger create/search/import smoke evidence. |
| `backend/src/main/java/com/playdata/calen/ledger/config/LedgerEntryChangeHistorySchemaUpdater.java` | Existing ledger change-history JSON/text shape. | `V20260630_009__ledger_entry_change_history_fields.sql` now covers the schema; deletion still requires staging Flyway startup proof and restore-history smoke evidence. |
| `backend/src/main/java/com/playdata/calen/travel/config/TravelMediaAssetSchemaUpdater.java` | Existing travel media GPS/representative columns and indexes. | `V20260630_010__travel_media_asset_metadata_fields.sql` now covers the schema; deletion still requires staging Flyway startup proof and travel media upload/map smoke evidence. |
| `backend/src/main/java/com/playdata/calen/travel/config/TravelPhotoClusterSchemaUpdater.java` | Existing travel photo cluster tables and membership indexes. | `V20260630_011__travel_photo_cluster_tables.sql` now covers the schema; deletion still requires staging Flyway startup proof and map cluster smoke evidence. |
| `backend/src/main/java/com/playdata/calen/travel/config/TravelRouteSchemaUpdater.java` | Existing route path, style, and GPX fields. | `V20260630_008__travel_route_segment_fields.sql` now covers the schema; deletion still requires staging Flyway startup proof and route/GPX smoke evidence. |

Retire one legacy updater at a time. Removing one requires: a versioned migration, updated inventory/evidence rows, a backup/restore rollback note, staging startup evidence with Flyway enabled, and deletion of the class from both this table and `scripts/verify-db-migrations.ps1`.
## Migration Operational Evidence

| Migration | Rehearsal scope | Rollback or restore note | Legacy updater impact |
| --- | --- | --- | --- |
| `V20260629_000__baseline_marker.sql` | Existing non-empty DB starts with Flyway baseline enabled in staging. | Disable Flyway and restore the pre-rehearsal DB backup if baseline metadata blocks startup. | No updater retired. |
| `V20260629_001__drive_download_link_access_logs.sql` | Public link download creates and queries access-log rows. | Export logs if needed, then restore the DB backup or drop the new log table in a controlled maintenance window. | New Flyway-managed table. |
| `V20260629_002__user_notifications.sql` | Notification creation, listing, and read-state updates run after migration. | Restore the DB backup if notification state must be preserved; otherwise drop the notification table after draining pending alerts. | New Flyway-managed table. |
| `V20260629_003__ledger_classification_rules.sql` | Rule create/update/delete and OCR/import rule lookup run after migration. | Export user rules before rollback and restore from backup if rule state must be preserved. | New Flyway-managed table. |
| `V20260629_004__ledger_ai_history_provider.sql` | AI analysis history save/list/delete covers provider/model queries on databases where the legacy table already exists. | Restore from backup if provider metadata is required; dropping the column loses provider attribution. | Earlier provider slice assumes the AI history table already exists; keep paired with `V20260630_013__ledger_ai_analysis_history_base.sql` and staging Flyway ordering evidence before retiring the updater. |
| `V20260629_005__drive_item_versions.sql` | File overwrite creates version rows and download/restore reads the expected version. | Restore from backup if versions must be preserved; table drop is only safe after confirming no version history is needed. | New Flyway-managed table. |
| `V20260629_006__drive_share_permissions.sql` | Direct share create/update/read enforces view/download/edit levels. | Restore from backup before downgrading; defaulting permissions during rollback can over-grant access. | New Flyway-managed columns/indexes for drive shares. |
| `V20260629_007__drive_direct_share_access_log_index.sql` | Direct share download audit queries use status and share/user lookup indexes. | Index rollback is low data-risk, but restore backup if paired with access-log schema changes. | Tightens Flyway-managed access-log performance. |
| `V20260630_008__travel_route_segment_fields.sql` | Route create/edit, map display, and GPX attachment smoke paths run after Flyway applies the field migration. | Restore the pre-migration DB backup if route field conversion fails; dropping the added style/GPX columns loses route presentation metadata. | Fully overlaps `TravelRouteSchemaUpdater`; updater remains until staging Flyway startup evidence permits deletion. |
| `V20260630_009__ledger_entry_change_history_fields.sql` | Entry edit/delete restore-history views read summary and before/after/change JSON after Flyway applies the field migration. | Restore the pre-migration DB backup if JSON/text conversion fails; dropping `changes_json` loses detailed diff payloads. | Fully overlaps `LedgerEntryChangeHistorySchemaUpdater`; updater remains until staging Flyway startup evidence permits deletion. |
| `V20260630_010__travel_media_asset_metadata_fields.sql` | Travel photo upload, GPS extraction, map clustering, and representative-photo override smoke paths run after Flyway applies the metadata migration. | Restore the pre-migration DB backup if metadata/index conversion fails; dropping GPS/representative columns loses map and representative-photo state. | Fully overlaps `TravelMediaAssetSchemaUpdater`; updater remains until staging Flyway startup evidence permits deletion. |
| `V20260630_011__travel_photo_cluster_tables.sql` | Map photo cluster rebuild/detail views create and read cluster/member rows after Flyway applies the table migration. | Restore the pre-migration DB backup if cluster table creation fails; dropping cluster tables loses derived map clustering state and requires rebuild. | Fully overlaps `TravelPhotoClusterSchemaUpdater`; updater remains until staging Flyway startup evidence permits deletion. |
| `V20260630_012__ledger_entry_operational_fields.sql` | Ledger create/search/import, foreign-currency display, travel expense linkage, and category/payment lookup flows run after Flyway applies the field/index migration. | Restore the pre-migration DB backup if ledger field/index migration fails; dropping foreign-currency/travel-link fields loses enriched ledger metadata. | Fully overlaps `LedgerEntrySchemaUpdater`; updater remains until staging Flyway startup evidence permits deletion. |
| `V20260630_013__ledger_ai_analysis_history_base.sql` | AI analysis save/list/detail/delete, latest matching reuse, retention cleanup, and provider/model history queries run after Flyway applies the base table/index migration. | Restore the pre-migration DB backup if AI history migration fails; dropping the table loses advisory analysis history and failure records. | Fully overlaps `LedgerAiAnalysisSchemaUpdater`; updater remains until staging Flyway startup and provider migration ordering evidence permits deletion. |

## Operating Rules

| Rule | Reason |
| --- | --- |
| Add every new schema change as a `VYYYYMMDD_NNN__description.sql` migration. | Gives rollback/audit evidence, makes deploys reproducible, and avoids startup-time schema mutation. |
| Update the Current Migration Inventory and Migration Operational Evidence tables for every tracked migration. | Keeps review and CI focused on deploy rehearsal, rollback, and legacy updater impact instead of filename checks only. |
| Run `scripts/verify-db-migrations.ps1` before merging schema work. | Catches duplicate versions, filename drift, missing baseline marker, undocumented migrations, missing operational evidence, unexpected legacy startup schema updaters, and new `ApplicationRunner`/`CommandLineRunner` DDL before CI. |
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
- A passing `scripts/verify-db-migrations.ps1` result or CI `migration-discipline` job, including the legacy updater inventory and startup DDL freeze checks.
- A note explaining whether an existing `*SchemaUpdater` was retained, reduced, or removed.
- A rollback note for data-preserving rollback or restore-from-backup rollback.
- A staging startup check with Flyway enabled before production promotion.

## Latest schema slices

- `V20260629_005__drive_item_versions.sql` adds the CalenDrive file version ledger table and owner/item indexes.
- `V20260629_006__drive_share_permissions.sql` adds explicit direct-share permission levels.
- `V20260629_007__drive_direct_share_access_log_index.sql` adds direct-share access-log lookup indexes.
- `V20260630_008__travel_route_segment_fields.sql` moves Travel route segment path/style/GPX field DDL into Flyway.
- `V20260630_009__ledger_entry_change_history_fields.sql` moves ledger entry change-history summary and JSON/text field DDL into Flyway.
- `V20260630_010__travel_media_asset_metadata_fields.sql` moves Travel media GPS/representative metadata fields and indexes into Flyway.
- `V20260630_011__travel_photo_cluster_tables.sql` moves Travel photo cluster tables and indexes into Flyway.
- `V20260630_012__ledger_entry_operational_fields.sql` moves ledger entry foreign-currency/travel-link fields and ledger/category/payment indexes into Flyway.
- `V20260630_013__ledger_ai_analysis_history_base.sql` moves Ledger AI analysis history table, provider compatibility, and owner/range/mode/provider indexes into Flyway.
- All six legacy `*SchemaUpdater` classes now have Flyway overlap but remain documented temporary exceptions until staging Flyway startup, provider ordering, and smoke evidence allow deletion.
- Startup DDL freeze is now enforced: new schema mutation runners must be rejected unless they retire one documented legacy exception with migration evidence.
