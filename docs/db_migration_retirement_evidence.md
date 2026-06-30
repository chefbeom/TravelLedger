# DB Migration Retirement Evidence

Updated: 2026-06-30

This ledger separates Flyway overlap from permission to delete legacy startup schema updaters. A `*SchemaUpdater` may be removed only after its row is moved from `Pending` to `Ready`, the evidence fields are filled with real staging results, the Ready evidence bundle is concrete, and `scripts/verify-db-migrations.ps1` plus the release checklist are updated in the same change.

## Evidence fields required before retirement

| Field | Requirement |
| --- | --- |
| Staging Flyway startup proof | Date, environment, commit SHA, `DB_MIGRATION_ENABLED=true`, `APP_SCHEMA_LEGACY_UPDATERS_ENABLED=false`, `DB_MIGRATION_BASELINE_ON_MIGRATE` value, and confirmation that startup completed without legacy updater assistance. |
| Flyway history proof | `flyway_schema_history` contains the baseline marker plus every required migration for the retired updater. |
| Smoke evidence | Feature-specific read/write flow listed in the table below completed after Flyway startup. |
| Rollback/restore evidence | Backup artifact or restore point, rollback decision, and restore command/runbook link are recorded. |
| Code removal evidence | Updater class deletion, verifier expected-list update, and release note happen in the same pull request/commit. |
| Ready evidence bundle | A `Ready` row must include concrete evidence references for startup proof, `flyway_schema_history`, smoke result, backup/restore artifact, and the removal commit. |
| Blocker/exception note | A `Pending` row must keep the current blocker clear when Flyway overlap exists but deletion is not permitted. |

Ready rows must include concrete evidence references and cannot keep `TBD` placeholders. At minimum, the row must name `DB_MIGRATION_ENABLED=true`, `APP_SCHEMA_LEGACY_UPDATERS_ENABLED=false`, `flyway_schema_history`, a backup artifact, a restore command or runbook, and the commit or pull request that removes the legacy updater.

## Legacy updater retirement ledger

| Status | Legacy updater | Required migrations | Required smoke evidence | Evidence bundle / blocker | Evidence owner/date |
| --- | --- | --- | --- | --- | --- |
| Pending | `LedgerAiAnalysisSchemaUpdater` | `V20260629_004__ledger_ai_history_provider.sql`, `V20260630_013__ledger_ai_analysis_history_base.sql` | AI history save, list, detail, delete, latest-match reuse, retention cleanup, and provider/model query. | Blocked: staging Flyway startup proof, Flyway history proof, smoke evidence, and rollback/restore evidence are not recorded yet. | TBD |
| Pending | `LedgerEntrySchemaUpdater` | `V20260630_012__ledger_entry_operational_fields.sql` | Ledger create, search, Excel/OCR import, foreign-currency display, travel expense linkage, category lookup, and payment lookup. | Blocked: staging Flyway startup proof, Flyway history proof, smoke evidence, and rollback/restore evidence are not recorded yet. | TBD |
| Pending | `LedgerEntryChangeHistorySchemaUpdater` | `V20260630_009__ledger_entry_change_history_fields.sql` | Entry edit/delete change-history list/detail and restore-history JSON display. | Blocked: staging Flyway startup proof, Flyway history proof, smoke evidence, and rollback/restore evidence are not recorded yet. | TBD |
| Pending | `TravelMediaAssetSchemaUpdater` | `V20260630_010__travel_media_asset_metadata_fields.sql` | Travel photo upload, GPS extraction, map display, and representative-photo override. | Blocked: staging Flyway startup proof, Flyway history proof, smoke evidence, and rollback/restore evidence are not recorded yet. | TBD |
| Pending | `TravelPhotoClusterSchemaUpdater` | `V20260630_011__travel_photo_cluster_tables.sql` | Map photo cluster rebuild, marker detail, cluster detail, and member ordering. | Blocked: staging Flyway startup proof, Flyway history proof, smoke evidence, and rollback/restore evidence are not recorded yet. | TBD |
| Pending | `TravelRouteSchemaUpdater` | `V20260630_008__travel_route_segment_fields.sql` | Travel route create/edit, route map display, GPX attachment, and GPX readback. | Blocked: staging Flyway startup proof, Flyway history proof, smoke evidence, and rollback/restore evidence are not recorded yet. | TBD |
| Pending | `HouseholdGoalSchemaUpdater` | `V20260630_014__household_goals.sql` | Household goal create, list, update, archive, and goal-progress notification. | Blocked: staging Flyway startup proof, Flyway history proof, smoke evidence, and rollback/restore evidence are not recorded yet. | TBD |

## Retirement procedure

1. Back up the staging database and object storage metadata needed for the affected feature.
2. Start staging with `DB_MIGRATION_ENABLED=true` and record the exact commit SHA and environment.
3. Confirm `flyway_schema_history` includes the baseline marker and every required migration in the row.
4. Run the row-specific smoke evidence after startup and record the result here.
5. Confirm restore/rollback instructions and artifact IDs before deleting code.
6. Replace the blocker text with the Ready evidence bundle, including startup proof, Flyway history proof, smoke result, backup/restore artifact, and removal commit or pull request.
7. Delete exactly one legacy updater at a time, update `docs/db_migration_strategy.md`, update `scripts/verify-db-migrations.ps1`, and include this evidence row in the same commit.

## Current status

All seven legacy updaters have Flyway overlap, but none are marked `Ready` here. Deleting any updater before real staging evidence is recorded should be treated as a release blocker.