# DB Migration Retirement Evidence

Updated: 2026-06-30

This ledger separates Flyway overlap from permission to delete legacy startup schema updaters. A `*SchemaUpdater` may be removed only after its row is moved from `Pending` to `Ready`, the evidence fields are filled with real staging results, and `scripts/verify-db-migrations.ps1` plus the release checklist are updated in the same change.

## Evidence fields required before retirement

| Field | Requirement |
| --- | --- |
| Staging Flyway startup proof | Date, environment, commit SHA, `DB_MIGRATION_ENABLED=true`, `DB_MIGRATION_BASELINE_ON_MIGRATE` value, and confirmation that startup completed without legacy updater assistance. |
| Flyway history proof | `flyway_schema_history` contains the baseline marker plus every required migration for the retired updater. |
| Smoke evidence | Feature-specific read/write flow listed in the table below completed after Flyway startup. |
| Rollback/restore evidence | Backup artifact or restore point, rollback decision, and restore command/runbook link are recorded. |
| Code removal evidence | Updater class deletion, verifier expected-list update, and release note happen in the same pull request/commit. |

## Legacy updater retirement ledger

| Status | Legacy updater | Required migrations | Required smoke evidence | Evidence owner/date |
| --- | --- | --- | --- | --- |
| Pending | `LedgerAiAnalysisSchemaUpdater` | `V20260629_004__ledger_ai_history_provider.sql`, `V20260630_013__ledger_ai_analysis_history_base.sql` | AI history save, list, detail, delete, latest-match reuse, retention cleanup, and provider/model query. | TBD |
| Pending | `LedgerEntrySchemaUpdater` | `V20260630_012__ledger_entry_operational_fields.sql` | Ledger create, search, Excel/OCR import, foreign-currency display, travel expense linkage, category lookup, and payment lookup. | TBD |
| Pending | `LedgerEntryChangeHistorySchemaUpdater` | `V20260630_009__ledger_entry_change_history_fields.sql` | Entry edit/delete change-history list/detail and restore-history JSON display. | TBD |
| Pending | `TravelMediaAssetSchemaUpdater` | `V20260630_010__travel_media_asset_metadata_fields.sql` | Travel photo upload, GPS extraction, map display, and representative-photo override. | TBD |
| Pending | `TravelPhotoClusterSchemaUpdater` | `V20260630_011__travel_photo_cluster_tables.sql` | Map photo cluster rebuild, marker detail, cluster detail, and member ordering. | TBD |
| Pending | `TravelRouteSchemaUpdater` | `V20260630_008__travel_route_segment_fields.sql` | Travel route create/edit, route map display, GPX attachment, and GPX readback. | TBD |

## Retirement procedure

1. Back up the staging database and object storage metadata needed for the affected feature.
2. Start staging with `DB_MIGRATION_ENABLED=true` and record the exact commit SHA and environment.
3. Confirm `flyway_schema_history` includes the baseline marker and every required migration in the row.
4. Run the row-specific smoke evidence after startup and record the result here.
5. Confirm restore/rollback instructions and artifact IDs before deleting code.
6. Delete exactly one legacy updater at a time, update `docs/db_migration_strategy.md`, update `scripts/verify-db-migrations.ps1`, and include this evidence row in the same commit.

## Current status

All six legacy updaters have Flyway overlap, but none are marked `Ready` here. Deleting any updater before real staging evidence is recorded should be treated as a release blocker.