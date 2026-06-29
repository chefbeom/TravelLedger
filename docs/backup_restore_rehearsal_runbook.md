# Backup Restore Rehearsal Runbook

Updated: 2026-06-29

This runbook is the evidence checklist for proving that TravelLedger backups can be restored. It complements `docs/dbtogdrive.md` for backup setup and `docs/db_restore_from_gdrive.md` for detailed restore commands.

## Goal

A backup is considered reliable only after a restore rehearsal proves that:

- The latest remote backup can be listed and downloaded from the configured rclone remote.
- The archive can be decompressed or unpacked without corruption.
- The database can be restored into an isolated rehearsal database before touching production.
- Key tables contain plausible counts after restore.
- Operators know the exact fallback point before any production restore.
- Evidence is recorded with timestamps and artifact names.

## Scope

| Backup type | Source | Remote directory | Rehearsal target |
| --- | --- | --- | --- |
| MariaDB logical dump | `AdminDataManagementService` or `deploy/oci/scripts/backup-to-gdrive.sh` | `DATA_OPS_BACKUP_REMOTE_DIR`, default `calen-db-backups` | `${DB_NAME}_restore_test` database |
| MinIO archive | `MinioBackupArchiveService` through admin data operations | `DATA_OPS_MINIO_BACKUP_REMOTE_DIR`, default `calen-minio-backups` | Temporary object-storage restore workspace |
| Pre-production fallback dump | Manual pre-restore dump | `/opt/calen-backup/restore` | Local rollback artifact |

## Rehearsal Cadence

| Trigger | Required rehearsal |
| --- | --- |
| Monthly operations check | Restore latest DB backup into `${DB_NAME}_restore_test` and record table counts. |
| Before schema migration | Restore latest DB backup into rehearsal DB before enabling Flyway or applying destructive migration. |
| Before production restore | Create a fresh pre-restore dump, then rehearse the chosen backup against a test DB. |
| After backup implementation change | Rehearse both DB and MinIO backup artifacts. |

## Preflight Checklist

| Check | Evidence to record |
| --- | --- |
| `.env` loaded without printing secrets | Operator initials and timestamp. |
| `DATA_OPS_BACKUP_REMOTE_NAME` points to the expected rclone remote | Remote name only, never token/config contents. |
| Latest DB backup is visible remotely | Backup filename and size/date from `rclone lsf` or admin UI. |
| Local restore workspace exists | Path, for example `/opt/calen-backup/restore`. |
| Production backend is not stopped for rehearsal | Confirmation that rehearsal targets an isolated DB. |
| Backup failure/staleness alerts are green or acknowledged | Alert name and state. |

## DB Restore Rehearsal Procedure

Use `docs/db_restore_from_gdrive.md` for command details. Record evidence for each step instead of copying secrets or full dumps into the ticket.

1. Select the backup artifact.
2. Download it into `/opt/calen-backup/restore`.
3. Create or replace `${DB_NAME}_restore_test`.
4. Import the backup into `${DB_NAME}_restore_test`.
5. Run smoke queries against the rehearsal DB.
6. Record counts for identity, ledger, drive, travel, and AI-history tables that exist in the restored schema.
7. Drop the rehearsal DB after evidence is captured, unless it is intentionally retained for investigation.

Suggested smoke-query evidence:

| Check | Example evidence |
| --- | --- |
| Schema restored | `SHOW TABLES` returns expected application tables. |
| Users exist | `app_users` count is non-zero for production-like backup. |
| Ledger exists | `ledger_entries` count matches the expected order of magnitude. |
| Drive metadata exists | `drive_items` count is plausible if CalenDrive is enabled. |
| AI history exists | AI analysis history table count is plausible if AI is enabled. |
| Migration state visible | `flyway_schema_history` exists when Flyway has been enabled in that environment. |

## MinIO Restore Rehearsal Procedure

MinIO restore rehearsals must avoid overwriting the production bucket. Use a temporary bucket, path prefix, or isolated MinIO instance.

1. Select the MinIO backup artifact from `DATA_OPS_MINIO_BACKUP_REMOTE_DIR`.
2. Download or stream the archive into a temporary restore workspace.
3. Verify the `_backup-manifest.json` entry exists.
4. Restore a small sample object set into a temporary target.
5. Compare sample object count, names, and byte sizes with the manifest.
6. Delete the temporary target after evidence is captured.

## Encryption and Secret Handling

| Rule | Reason |
| --- | --- |
| Do not paste `.env`, `rclone.conf`, DB passwords, OAuth tokens, or signed URLs into evidence. | Backup evidence often ends up in tickets or chat. |
| Prefer encrypted backup artifacts before storing outside the server trust boundary. | Database dumps and object archives contain personal data. |
| If encryption is enabled, rehearse decrypt-and-restore, not only download. | A backup that cannot be decrypted is not recoverable. |
| Store encryption keys outside the backup remote. | A compromised remote should not expose both data and keys. |

Minimum encryption acceptance criteria:

- The chosen tool and key owner are documented.
- A second admin can locate the key without chat history or personal notes.
- A test decrypt succeeds during the rehearsal.
- Failed decrypt produces an alert or manual incident note.

## Evidence Template

Copy this block into the operations ticket or release note after each rehearsal.

```text
Rehearsal date/time:
Operator:
Environment:
Backup type: DB | MinIO | both
Remote name:
Remote directory:
Artifact name:
Artifact timestamp:
Artifact size:
Restore target:
Restore started at:
Restore finished at:
Smoke checks:
  - schema/table list:
  - app_users count:
  - ledger_entries count:
  - drive_items count:
  - travel tables count:
  - AI history count:
Alerts checked:
Cleanup completed:
Production data touched: no | yes, explain
Result: pass | fail
Follow-up issues:
```

## Failure Handling

| Failure | Immediate action |
| --- | --- |
| Remote backup missing | Treat as backup incident; check scheduler, rclone config, and `CalenDataOpsBackupStale`. |
| Download fails | Check rclone quota, credentials, network path, and remote folder names. |
| Decompression fails | Mark artifact unusable and rehearse the previous backup. |
| Import fails | Keep restore workspace for investigation and capture the first failing SQL error only. |
| Smoke counts are implausible | Do not promote migration or production restore until data scope is explained. |
| MinIO manifest missing | Treat object backup as incomplete. |

## Release Gate

A release that changes backup, restore, schema migration, storage layout, or admin data-management behavior should include:

- A successful rehearsal record using this runbook.
- A named fallback artifact created before risky production work.
- A statement that production data was not touched during rehearsal.
- A follow-up ticket for encryption if backups are still stored as plaintext archives.