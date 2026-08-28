# Data Operations Backup Runbook

This runbook describes the application-managed backup flow for a project tenant.

## Runtime contract

- Database backup runs at 06:00 KST by default.
- MinIO logical archive runs at 06:30 KST by default.
- `DATA_OPS_DB_BACKUP_CRON` and `DATA_OPS_MINIO_BACKUP_CRON` override the schedules.
- `DATA_OPS_BACKUP_ZONE` defaults to `Asia/Seoul`.
- Local staging is under `DATA_OPS_BACKUP_WORKDIR` (`/opt/calen-backup` by default).
- rclone config is read from `/app/.config/rclone-host/rclone.conf` by default; the host directory is mounted read-only.

## Storage layout

Existing Google Drive flat paths remain unchanged for the administrator list and DB restore:

```text
db-backup:calen-db-backups/calen-YYYY-MM-DD-HHmmss.sql.gz
db-backup:calen-minio-backups/calen-minio-YYYY-MM-DD-HHmmss.zip
```

Each successful artifact is also uploaded below the project and date path:

```text
db-backup:calen-archive/<project-key>/YYYY/MM/DD/<artifact>
```

Set `DATA_OPS_PROJECT_KEY` to the tenant/project slug. The secondary storage path, when enabled, follows the same layout:

```text
<DATA_OPS_SECONDARY_BACKUP_DIR>/<project-key>/YYYY/MM/DD/<artifact>
```

The `.sha256` sidecars are stored with the dated archive and secondary copy. A flat sidecar is also uploaded for integrity checks, but administrator lists filter sidecars out and show only original backup artifacts.

## Failure and retention rules

1. The artifact is written to local staging and checksummed first.
2. If `DATA_OPS_SECONDARY_BACKUP_ENABLED=true`, the artifact and sidecar are copied append-only to the dated secondary directory.
3. The flat Google Drive artifact, dated Google Drive artifact, and both sidecars must all upload successfully.
4. Any staging, secondary, quota, or Google Drive failure marks the run as failed and leaves local staging files for diagnosis or retry.
5. Existing secondary files are never overwritten. A duplicate filename is a failed run, not a replacement.

Google Drive quota/rate-limit errors are failures and must not be treated as a successful fallback.

## Restore scope

The existing database restore endpoint remains destructive: it clears the current database and imports the selected SQL dump. Take and verify a pre-restore backup before using it in production.

There is currently no MinIO restore endpoint and no Redis backup/restore contract in the application. MinIO archives are logical backup artifacts; Redis is an ephemeral/state dependency and remains an operational gap until a separate restore design is approved.

## Deployment checks

- Mount the personal server HDD/NFS path on the app host and set `DATA_OPS_SECONDARY_BACKUP_DIR` to that mount.
- Set `DATA_OPS_PROJECT_KEY` uniquely for every tenant.
- Mount the rclone config read-only and keep `DATA_OPS_RCLONE_CONFIG_PATH` pointed at `/app/.config/rclone-host/rclone.conf`.
- Verify the rclone remote and both existing flat directories before enabling scheduled jobs.
- The legacy root cron calling `/opt/calen-backup/backup-to-gdrive.sh` is separate from the Spring scheduler. Disable or remove that duplicate job after confirming the application scheduler is active; this code does not change the root crontab.
- Never run `docker compose down -v` on a production data stack during this migration.

After deployment, run one manual DB backup and one manual MinIO backup, verify local/secondary/flat/date destinations, then verify that the administrator list contains only `.sql.gz` or `.zip` artifacts.
