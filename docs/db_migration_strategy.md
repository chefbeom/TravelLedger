# DB Migration Strategy

Updated: 2026-10-11

## Current Transition State

- Source audit clarification (2026-10-16): turning the legacy switch off disables only nine updater beans, not all startup DDL. Five other existing bootstraps remain outside it. See [backend schema governance audit](backend_schema_governance_audit.md) for the complete source inventory, migration bootstrap gaps, and test limits; this does not authorize changing deployment defaults or mark any updater Ready for retirement.
- Flyway is available in the backend build with `flyway-core` and `flyway-mysql`.
- Flyway is disabled by default with `DB_MIGRATION_ENABLED=false` while legacy startup updaters still exist.
- Legacy startup updaters are enabled by default with `APP_SCHEMA_LEGACY_UPDATERS_ENABLED=true`; staging Flyway rehearsals can set it to `false` to prove startup without updater assistance before deleting a class.
- Hibernate `ddl-auto: update` remains in place during the transition so existing local and compose workflows keep working.
- `backend/src/main/resources/db/migration` contains a baseline marker plus versioned migrations for access logs, the retired legacy user-notification table, classification rules, AI history, drive versions/sharing/travel links/indexes, one-time jobs, shared leases, image/AI/Excel job recovery, travel query/cluster indexes, recurring ledger rules, account email verification, record sharing, household goals, and the ledger embedding outbox.
- `scripts/verify-db-migrations.ps1` and the CI `migration-discipline` job check migration naming, duplicate versions, baseline marker presence, migration inventory documentation, operational evidence notes, the expected legacy `*SchemaUpdater` inventory, retirement evidence ledger coverage, Ready evidence placeholders, and unexpected startup DDL runners.

## Current Migration Inventory

| Version | Purpose |
| --- | --- |
| `V20260629_000__baseline_marker.sql` | No-op baseline marker for controlled Flyway adoption. |
| `V20260629_001__drive_download_link_access_logs.sql` | Drive public download access log table. |
| `V20260629_002__user_notifications.sql` | Retired legacy user-notification table; retained without read/write access until an explicitly approved data-purge migration. |
| `V20260629_003__ledger_classification_rules.sql` | Ledger classification rule table. |
| `V20260629_004__ledger_ai_history_provider.sql` | Fresh-safe AI history table bootstrap, provider column compatibility, and provider/model query index. |
| `V20260629_005__drive_item_versions.sql` | CalenDrive file version ledger table and owner/item indexes. |
| `V20260629_006__drive_share_permissions.sql` | Drive share permission level columns and supporting indexes. |
| `V20260629_007__drive_direct_share_access_log_index.sql` | Direct drive share access-log status and lookup indexes. |
| `V20260630_008__travel_route_segment_fields.sql` | Travel route segment path/style/GPX fields previously enforced by startup DDL. |
| `V20260630_009__ledger_entry_change_history_fields.sql` | Ledger entry change-history summary and JSON/text fields previously enforced by startup DDL. |
| `V20260630_010__travel_media_asset_metadata_fields.sql` | Travel media GPS, representative override, GPS extraction timestamp, and lookup indexes previously enforced by startup DDL. |
| `V20260630_011__travel_photo_cluster_tables.sql` | Travel photo cluster and cluster-member tables plus lookup/uniqueness indexes previously enforced by startup DDL. |
| `V20260630_012__ledger_entry_operational_fields.sql` | Ledger entry foreign-currency/travel-link fields and ledger/category/payment lookup indexes previously enforced by startup DDL. |
| `V20260630_013__ledger_ai_analysis_history_base.sql` | Ledger AI analysis history base table, provider column compatibility, and owner/range/mode/provider indexes previously enforced by startup DDL plus provider migration. |
| `V20260630_014__household_goals.sql` | Owner-scoped household goal table and owner/status plus owner/due-date indexes previously enforced by startup DDL. |
| `V20260707_015__drive_travel_links.sql` | Drive item system-managed flag, source type/reference, and owner/source index for Travel-linked items. |
| `V20260707_016__app_one_time_jobs.sql` | Durable completion ledger for idempotent one-time jobs. |
| `V20260710_017__travel_query_indexes.sql` | Owner, public status, plan order, expense, and media lookup indexes for Travel queries. |
| `V20260829_018__recurring_ledger.sql` | Owner-scoped recurring ledger rules and schedule metadata. |
| `V20260829_019__recurring_ledger_schedule_types.sql` | Schedule type and interval columns plus legacy monthly-date normalization. |
| `V20260829_020__recurring_ledger_month_intervals.sql` | Monthly interval compatibility and default normalization. |
| `V20260831_021__account_email_verification_social_login.sql` | Account email/verification state, verification tokens, and social-login identity schema. |
| `V20261003_022__drive_query_indexes.sql` | Drive owner/parent, modified-time, and type/trash query indexes. |
| `V20261003_023__shared_work_leases.sql` | Expiring distributed work-lease table and expiry index. |
| `V20261003_024__image_job_recovery.sql` | Image-analysis request effective-prompt field and recovery metadata. |
| `V20261003_025__analysis_job_recovery.sql` | AI analysis history prompt metadata and owner/client-request deduplication index. |
| `V20261003_026__excel_analysis_jobs.sql` | Owner-scoped asynchronous Excel analysis job table and status indexes. |
| `V20261003_027__analysis_job_versions.sql` | Optimistic-lock version columns for image, AI history, and Excel analysis jobs. |
| `V20261003_028__photo_cluster_refresh_jobs.sql` | Per-owner photo-cluster refresh generation and dirty-state table. |
| `V20261003_029__cluster_member_query_indexes.sql` | Owner/cluster/order and owner/media lookup indexes for cluster members. |
| `V20261003_030__record_sharing.sql` | Owner-group ledger/travel record share requests, snapshots, status, and inbox/sent indexes. |
| `V20261003_031__record_share_memo.sql` | Optional memo field for shared record requests. |
| `V20261006_032__ledger_embedding_sync_jobs.sql` | Durable ledger embedding outbox, revision/claim/retry metadata, ready/expired-claim indexes. No source FK, payload, startup DDL or data backfill. |
| `V20261013_033__record_map.sql` | Owner-scoped places, many-to-many collections, dated visits/photos, and hashed revocable public collection links. No travel data rewrite or backfill. |

## Legacy Schema Updater Inventory

| Priority | Class | Retirement target |
| --- | --- | --- |
| 1 | `LedgerAiAnalysisSchemaUpdater` | Flyway overlap added in `V20260630_013__ledger_ai_analysis_history_base.sql`; retire after staging Flyway startup proof, AI history save/list/delete smoke evidence, and provider migration ordering rehearsal. |
| 2 | `LedgerEntrySchemaUpdater` | Flyway overlap added in `V20260630_012__ledger_entry_operational_fields.sql`; retire after staging Flyway startup proof and ledger create/search/import smoke evidence. |
| 3 | `LedgerEntryChangeHistorySchemaUpdater` | Flyway overlap added in `V20260630_009__ledger_entry_change_history_fields.sql`; retire after staging Flyway startup proof and restore-history smoke evidence. |
| 4 | `TravelMediaAssetSchemaUpdater` | Flyway overlap added in `V20260630_010__travel_media_asset_metadata_fields.sql`; retire after staging Flyway startup proof and travel media upload/map smoke evidence. |
| 5 | `TravelPhotoClusterSchemaUpdater` | Flyway overlap added in `V20260630_011__travel_photo_cluster_tables.sql`; retire after staging Flyway startup proof and map cluster smoke evidence. |
| 6 | `TravelRouteSchemaUpdater` | Flyway overlap added in `V20260630_008__travel_route_segment_fields.sql`; retire after staging Flyway startup proof and route/GPX smoke evidence. |
| 7 | `HouseholdGoalSchemaUpdater` | Flyway overlap added in `V20260630_014__household_goals.sql`; retire after staging Flyway startup proof and household goal create/list/update/archive smoke evidence. |
| 8 | `LedgerImageAnalysisRequestSchemaUpdater` | Migrations `V20261003_024__image_job_recovery.sql` and `V20261003_027__analysis_job_versions.sql` cover later fields; the base table still has no Flyway bootstrap, so add/rehearse that migration before retirement. |
| 9 | `TravelMapShareLinkSchemaUpdater` | No matching Flyway migration exists yet; add and rehearse a table migration before retirement. |


## Startup DDL Freeze

No new `ApplicationRunner` or `CommandLineRunner` may execute `CREATE TABLE`, `ALTER TABLE`, `DROP TABLE`, `CREATE INDEX`, or `ALTER INDEX`. New schema changes must be Flyway migrations only. The existing startup DDL classes below are temporary legacy exceptions, must stay guarded by `APP_SCHEMA_LEGACY_UPDATERS_ENABLED`, and are allowed only while their matching migration evidence is prepared.

| Legacy exception | Allowed reason | Retirement evidence required |
| --- | --- | --- |
| `backend/src/main/java/com/playdata/calen/ledger/config/LedgerAiAnalysisSchemaUpdater.java` | Existing AI history table/index bootstrap. | `V20260630_013__ledger_ai_analysis_history_base.sql` now covers the schema; deletion still requires staging Flyway startup proof, AI history save/list/delete smoke evidence, and provider migration ordering rehearsal. |
| `backend/src/main/java/com/playdata/calen/ledger/config/LedgerEntrySchemaUpdater.java` | Existing ledger entry currency, travel-link, category, payment, and search indexes. | `V20260630_012__ledger_entry_operational_fields.sql` now covers the schema; deletion still requires staging Flyway startup proof and ledger create/search/import smoke evidence. |
| `backend/src/main/java/com/playdata/calen/ledger/config/LedgerEntryChangeHistorySchemaUpdater.java` | Existing ledger change-history JSON/text shape. | `V20260630_009__ledger_entry_change_history_fields.sql` now covers the schema; deletion still requires staging Flyway startup proof and restore-history smoke evidence. |
| `backend/src/main/java/com/playdata/calen/travel/config/TravelMediaAssetSchemaUpdater.java` | Existing travel media GPS/representative columns and indexes. | `V20260630_010__travel_media_asset_metadata_fields.sql` now covers the schema; deletion still requires staging Flyway startup proof and travel media upload/map smoke evidence. |
| `backend/src/main/java/com/playdata/calen/travel/config/TravelPhotoClusterSchemaUpdater.java` | Existing travel photo cluster tables and membership indexes. | `V20260630_011__travel_photo_cluster_tables.sql` now covers the schema; deletion still requires staging Flyway startup proof and map cluster smoke evidence. |
| `backend/src/main/java/com/playdata/calen/travel/config/TravelRouteSchemaUpdater.java` | Existing route path, style, and GPX fields. | `V20260630_008__travel_route_segment_fields.sql` now covers the schema; deletion still requires staging Flyway startup proof and route/GPX smoke evidence. |
| `backend/src/main/java/com/playdata/calen/account/config/HouseholdGoalSchemaUpdater.java` | Existing household goal table and owner/status due-date indexes. | `V20260630_014__household_goals.sql` now covers the schema; deletion still requires staging Flyway startup proof and household goal create/list/update/archive smoke evidence. |
| `backend/src/main/java/com/playdata/calen/ledger/config/LedgerImageAnalysisRequestSchemaUpdater.java` | Creates the image-analysis request table and guards its recovery columns/indexes. | `V20261003_024__image_job_recovery.sql` and `V20261003_027__analysis_job_versions.sql` cover later fields, but the base table bootstrap remains an explicit migration gap before retirement. |
| `backend/src/main/java/com/playdata/calen/travel/config/TravelMapShareLinkSchemaUpdater.java` | Creates the persisted Travel map share-link table. | No matching Flyway migration exists yet; add one and rehearse Flyway-only startup before retiring this temporary exception. |

Retire one legacy updater at a time. Removing one requires: a versioned migration, updated inventory/evidence rows, a backup/restore rollback note, staging startup evidence with Flyway enabled, a `Ready` row in `docs/db_migration_retirement_evidence.md`, and deletion of the class from both this table and `scripts/verify-db-migrations.ps1`. A `Ready` row in `docs/db_migration_retirement_evidence.md` must include a concrete evidence bundle and no `TBD` placeholders.
## Migration Operational Evidence

| Migration | Rehearsal scope | Rollback or restore note | Legacy updater impact |
| --- | --- | --- | --- |
| `V20260629_000__baseline_marker.sql` | Existing non-empty DB starts with Flyway baseline enabled in staging. | Disable Flyway and restore the pre-rehearsal DB backup if baseline metadata blocks startup. | No updater retired. |
| `V20260629_001__drive_download_link_access_logs.sql` | Public link download creates and queries access-log rows. | Export logs if needed, then restore the DB backup or drop the new log table in a controlled maintenance window. | New Flyway-managed table. |
| `V20260629_002__user_notifications.sql` | The legacy table is no longer read or written by the application. | Preserve historical rows; a separate, explicitly approved migration is required before any purge. | Retired Flyway-managed table. |
| `V20260629_003__ledger_classification_rules.sql` | Rule create/update/delete and OCR/import rule lookup run after migration. | Export user rules before rollback and restore from backup if rule state must be preserved. | New Flyway-managed table. |
| `V20260629_004__ledger_ai_history_provider.sql` | Fresh Flyway bootstrap creates the AI history table when absent and keeps provider/model queries indexed. | Restore from backup if provider metadata is required; dropping the table or provider column loses advisory analysis history/provider attribution. | Now fresh-db safe and paired with `V20260630_013__ledger_ai_analysis_history_base.sql`, which keeps the remaining owner/range/mode indexes explicit before retiring the updater. |
| `V20260629_005__drive_item_versions.sql` | File overwrite creates version rows and download/restore reads the expected version. | Restore from backup if versions must be preserved; table drop is only safe after confirming no version history is needed. | New Flyway-managed table. |
| `V20260629_006__drive_share_permissions.sql` | Direct share create/update/read enforces view/download/edit levels. | Restore from backup before downgrading; defaulting permissions during rollback can over-grant access. | New Flyway-managed columns/indexes for drive shares. |
| `V20260629_007__drive_direct_share_access_log_index.sql` | Direct share download audit queries use status and share/user lookup indexes. | Index rollback is low data-risk, but restore backup if paired with access-log schema changes. | Tightens Flyway-managed access-log performance. |
| `V20260630_008__travel_route_segment_fields.sql` | Route create/edit, map display, and GPX attachment smoke paths run after Flyway applies the field migration. | Restore the pre-migration DB backup if route field conversion fails; dropping the added style/GPX columns loses route presentation metadata. | Fully overlaps `TravelRouteSchemaUpdater`; updater remains until staging Flyway startup evidence permits deletion. |
| `V20260630_009__ledger_entry_change_history_fields.sql` | Entry edit/delete restore-history views read summary and before/after/change JSON after Flyway applies the field migration. | Restore the pre-migration DB backup if JSON/text conversion fails; dropping `changes_json` loses detailed diff payloads. | Fully overlaps `LedgerEntryChangeHistorySchemaUpdater`; updater remains until staging Flyway startup evidence permits deletion. |
| `V20260630_010__travel_media_asset_metadata_fields.sql` | Travel photo upload, GPS extraction, map clustering, and representative-photo override smoke paths run after Flyway applies the metadata migration. | Restore the pre-migration DB backup if metadata/index conversion fails; dropping GPS/representative columns loses map and representative-photo state. | Fully overlaps `TravelMediaAssetSchemaUpdater`; updater remains until staging Flyway startup evidence permits deletion. |
| `V20260630_011__travel_photo_cluster_tables.sql` | Map photo cluster rebuild/detail views create and read cluster/member rows after Flyway applies the table migration. | Restore the pre-migration DB backup if cluster table creation fails; dropping cluster tables loses derived map clustering state and requires rebuild. | Fully overlaps `TravelPhotoClusterSchemaUpdater`; updater remains until staging Flyway startup evidence permits deletion. |
| `V20260630_012__ledger_entry_operational_fields.sql` | Ledger create/search/import, foreign-currency display, travel expense linkage, and category/payment lookup flows run after Flyway applies the field/index migration. | Restore the pre-migration DB backup if ledger field/index migration fails; dropping foreign-currency/travel-link fields loses enriched ledger metadata. | Fully overlaps `LedgerEntrySchemaUpdater`; updater remains until staging Flyway startup evidence permits deletion. |
| `V20260630_013__ledger_ai_analysis_history_base.sql` | AI analysis save/list/detail/delete, latest matching reuse, retention cleanup, and provider/model history queries run after Flyway applies the base table/index migration. | Restore the pre-migration DB backup if AI history migration fails; dropping the table loses advisory analysis history and failure records. | Fully overlaps `LedgerAiAnalysisSchemaUpdater`; updater remains until staging Flyway startup and provider migration ordering evidence permits deletion. |
| `V20260630_014__household_goals.sql` | Household goal create/list/update/archive paths run after Flyway applies the table/index migration. | Restore the pre-migration DB backup if goal migration fails; dropping the table loses personal goal progress and archived goal metadata. | Fully overlaps `HouseholdGoalSchemaUpdater`; updater remains until staging Flyway startup and household goal smoke evidence permits deletion. |
| `V20260707_015__drive_travel_links.sql` | Travel-linked drive items retain their source metadata and owner-scoped lookup after migration. | Restore the pre-migration backup if metadata/index application fails; preserve existing Drive item rows and links. | Adds fields/indexes used by Drive/Travel integration. |
| `V20260707_016__app_one_time_jobs.sql` | One-time job completion remains idempotent across restart. | Restore the pre-migration backup if the job ledger is unavailable; retain completion records needed to avoid duplicate processing. | New Flyway-managed table. |
| `V20260710_017__travel_query_indexes.sql` | Travel plan, budget, expense, and media queries retain owner scope and expected ordering. | Index-only rollback; restore the pre-migration backup if the database rejects the index changes. | Query indexes only. |
| `V20260829_018__recurring_ledger.sql` | Recurring rules list and schedule calculation read existing owner-scoped data after migration. | Restore the pre-migration backup before rolling back; preserve recurring rule rows and schedule settings. | New Flyway-managed table. |
| `V20260829_019__recurring_ledger_schedule_types.sql` | Existing recurring rules normalize to the default monthly-date schedule and remain readable. | Restore the pre-migration backup if normalization is incorrect; keep a copy of rules before any manual rollback. | Alters recurring schedule schema and values. |
| `V20260829_020__recurring_ledger_month_intervals.sql` | Monthly interval defaults remain valid for existing rules. | Restore the pre-migration backup if interval normalization is incorrect; preserve user schedule choices. | Alters recurring schedule values. |
| `V20260831_021__account_email_verification_social_login.sql` | Existing accounts remain readable and verification/social identity lookups use the new schema. | Restore the pre-migration backup if account metadata migration fails; preserve token/identity rows and avoid manual credential edits. | Adds account schema and verification-token storage. |
| `V20261003_022__drive_query_indexes.sql` | Drive owner/parent, modified-time, and trash/type queries retain owner scope and ordering. | Index-only rollback; restore the pre-migration backup if index creation fails. | Query indexes only. |
| `V20261003_023__shared_work_leases.sql` | Lease acquisition, expiry, and recovery remain consistent across backend instances. | Restore the pre-migration backup if lease schema blocks startup; expired lease rows may be safely reclaimed only by the lease protocol. | New Flyway-managed coordination table. |
| `V20261003_024__image_job_recovery.sql` | Image-analysis request recovery reads effective prompt metadata without losing existing jobs. | Restore the pre-migration backup if the column update fails; preserve request and result history. | Partially overlaps `LedgerImageAnalysisRequestSchemaUpdater`; base-table creation is still updater-only. |
| `V20261003_025__analysis_job_recovery.sql` | Existing duplicate client request IDs are normalized before the unique owner/client index is applied; retry and recovery paths remain available. | Restore the pre-migration backup if normalization or index creation fails; duplicate IDs are nulled only on older history rows. | AI history recovery metadata and deduplication index. |
| `V20261003_026__excel_analysis_jobs.sql` | Excel analysis jobs remain owner-scoped and status/recovery queries use the new table. | Restore the pre-migration backup if job persistence fails; retain rows needed to diagnose pending or failed imports. | New Flyway-managed table. |
| `V20261003_027__analysis_job_versions.sql` | Optimistic-lock version columns load for existing image, AI, and Excel analysis jobs. | Restore the pre-migration backup if version-column application fails; defaults initialize existing rows without replacing them. | Partially overlaps `LedgerImageAnalysisRequestSchemaUpdater`; base-table creation is still updater-only. |
| `V20261003_028__photo_cluster_refresh_jobs.sql` | Dirty/generation state can be read and reclaimed while cluster snapshots refresh. | Restore the pre-migration backup if refresh state is inconsistent; photo clusters can be rebuilt from source media. | New derived-state table. |
| `V20261003_029__cluster_member_query_indexes.sql` | Cluster detail and media lookup preserve owner/cluster ordering after index creation. | Index-only rollback; restore the pre-migration backup if index creation fails. | Query indexes only. |
| `V20261003_030__record_sharing.sql` | Share inbox/sent reads, response state, and imported-record references remain scoped to the intended participants. | Restore the pre-migration backup before rollback; share requests and snapshots are user records and must not be discarded. | New Flyway-managed table. |
| `V20261003_031__record_share_memo.sql` | Existing share requests remain readable with a null optional memo and new requests can persist the memo. | Restore the pre-migration backup if the column migration fails; preserve share request rows. | Adds optional field to `record_shares`. |
| `V20261006_032__ledger_embedding_sync_jobs.sql` | Pending rehearsal; compile only on 2026-10-06. Before promotion, verify MariaDB capture/rollback, bulk trash delete, claim/recovery, concurrent changes and API failure paths as described in `docs/embedding_automatic_sync.md`. No tests or DB changes run in this implementation step. | Disable API and capture before app rollback; preserve outbox/tombstones and DB backup. Do not drop pending work. App rollback does not undo remote points; plan authorized reconciliation separately. | New Flyway-only table; no legacy updater added, changed or retired. Required before capture/API enablement; `ddl-auto=update` does not create this JDBC-managed table. |
| `V20261013_033__record_map.sql` | Pending MariaDB/Flyway staging rehearsal. Verify owner/CSRF/media isolation, multi-collection membership, visit/photo retention, link expiry/revocation, and travel-reference preservation; local implementation evidence is tracked in `docs/record_map_implementation.md`. | Back up before rollout. On app rollback retain all six record-map tables and uploaded media; disabling the new UI does not require deleting records or changing legacy travel tables. | New Flyway-only schema; no startup updater added or retired. Existing travel records are not rewritten. |

## Operating Rules

| Rule | Reason |
| --- | --- |
| Add every new schema change as a `VYYYYMMDD_NNN__description.sql` migration. | Gives rollback/audit evidence, makes deploys reproducible, and avoids startup-time schema mutation. |
| Update the Current Migration Inventory and Migration Operational Evidence tables for every tracked migration. | Keeps review and CI focused on deploy rehearsal, rollback, and legacy updater impact instead of filename checks only. |
| Run `scripts/verify-db-migrations.ps1` before merging schema work. | Catches duplicate versions, filename drift, missing baseline marker, undocumented migrations, missing operational evidence, unexpected legacy startup schema updaters, and new `ApplicationRunner`/`CommandLineRunner` DDL before CI. |
| Do not edit a migration after it has run outside a local throwaway DB. | Flyway checksums should remain stable between environments. |
| Keep Flyway disabled in production until a staging rehearsal passes. | Avoids surprising startup failures while legacy schema is still updater-managed. |
| Convert one schema area at a time and remove the matching `*SchemaUpdater` only after migration evidence exists in `docs/db_migration_retirement_evidence.md`. | Reduces risk while replacing startup mutation logic and prevents Flyway-overlap work from being mistaken for deletion readiness; a Ready row must contain startup, Flyway history, smoke, backup/restore, and code-removal evidence. |
| Include a rollback note for every migration. | Production data rollback often means restore/replay rather than SQL down migrations. |

## Enablement Steps

1. Back up the target database.
2. Confirm the app starts with `DB_MIGRATION_ENABLED=false`.
3. Run backend tests and `scripts/verify-db-migrations.ps1` locally.
4. Enable `DB_MIGRATION_ENABLED=true` in a staging copy first.
5. Set `APP_SCHEMA_LEGACY_UPDATERS_ENABLED=false` for the staging proof run to remove the nine updater beans' assistance. Also account for Hibernate `update` and the five non-updater bootstraps in the source audit before claiming Flyway alone provides the schema.
6. Keep `DB_MIGRATION_BASELINE_ON_MIGRATE=true` for existing non-empty schemas without `flyway_schema_history`.
7. Confirm `flyway_schema_history` exists and contains the baseline marker plus expected migrations.
8. After all legacy schema updaters are converted, set Hibernate DDL mode to `validate` for non-local profiles.

## Release Gate

A release that adds or changes schema should include:

- A new versioned migration under `backend/src/main/resources/db/migration`.
- Updated Current Migration Inventory and Migration Operational Evidence rows.
- A passing `scripts/verify-db-migrations.ps1` result or CI `migration-discipline` job, including the legacy updater inventory and startup DDL freeze checks.
- A note explaining whether an existing `*SchemaUpdater` was retained, reduced, or removed.
- A rollback note for data-preserving rollback or restore-from-backup rollback.
- A staging startup check with Flyway enabled and `APP_SCHEMA_LEGACY_UPDATERS_ENABLED=false` before production promotion.
- A completed `docs/db_migration_retirement_evidence.md` row with a concrete Ready evidence bundle before deleting any legacy `*SchemaUpdater`.

## Latest schema slices

- `V20260629_005__drive_item_versions.sql` adds the CalenDrive file version ledger table and owner/item indexes.
- `V20260629_006__drive_share_permissions.sql` adds explicit direct-share permission levels.
- `V20260629_007__drive_direct_share_access_log_index.sql` adds direct-share access-log lookup indexes.
- `V20260630_008__travel_route_segment_fields.sql` moves Travel route segment path/style/GPX field DDL into Flyway.
- `V20260630_009__ledger_entry_change_history_fields.sql` moves ledger entry change-history summary and JSON/text field DDL into Flyway.
- `V20260630_010__travel_media_asset_metadata_fields.sql` moves Travel media GPS/representative metadata fields and indexes into Flyway.
- `V20260630_011__travel_photo_cluster_tables.sql` moves Travel photo cluster tables and indexes into Flyway.
- `V20260630_012__ledger_entry_operational_fields.sql` moves ledger entry foreign-currency/travel-link fields and ledger/category/payment indexes into Flyway.
- `V20260629_004__ledger_ai_history_provider.sql` is now fresh-db safe and bootstraps the AI history table before adding provider compatibility/indexes.
- `V20260630_013__ledger_ai_analysis_history_base.sql` keeps Ledger AI analysis history table, provider compatibility, and owner/range/mode/provider indexes explicit in Flyway.
- `V20260630_014__household_goals.sql` moves owner-scoped household goal table and progress lookup indexes into Flyway.
- The nine tracked legacy `*SchemaUpdater` classes remain guarded temporary exceptions until matching Flyway coverage, staging startup, smoke evidence, and `docs/db_migration_retirement_evidence.md` rows allow deletion; two still have explicit migration gaps.
- Startup DDL freeze is now enforced: new schema mutation runners must be rejected unless they retire one documented legacy exception with migration evidence.
- Legacy updater assistance can now be disabled with `APP_SCHEMA_LEGACY_UPDATERS_ENABLED=false` during Flyway staging rehearsals so retirement evidence proves the migration path rather than startup DDL fallback.
