# Environment Configuration Sync

This project keeps Spring configuration and deployment examples aligned with `scripts/verify-env-sync.ps1`.

## Checked files

By default the verifier compares environment placeholders in `backend/src/main/resources/application.yml` with:

- `.env.example`
- `.env.oci.app.example`

The data-only OCI stack is intentionally not checked against Spring placeholders because it does not run the backend application.

## Rules

- Every `${UPPER_CASE_ENV}` placeholder in `application.yml` must appear in each checked env example.
- Duplicate variable names fail the check.
- Malformed non-comment env lines fail the check.
- Boolean-like variables ending in `_ENABLED`, `_SSL`, `_SHOW_SQL`, `_FORMAT_SQL`, `_BASELINE_ON_MIGRATE`, or `_VALIDATE_ON_MIGRATE` must use `true` or `false`.
- Compose-only variables must match the allowlist in `scripts/verify-env-sync.ps1`.

## Operational notes

- `docker-compose.oci.app.yml` now prefers explicit Spring env names such as `DB_URL`, `DB_ID`, `MINIO_API`, `MINIO_NAME`, and `MINIO_SECRET` instead of relying only on compose-specific aliases.
- Keep `APP_LEDGER_AI_ENFORCE_PROVIDER_URL_ALLOWLIST=true` in production and set `APP_LEDGER_AI_ALLOWED_PROVIDER_HOSTS` to the exact LM Studio or n8n hosts.
- Keep `APP_LEDGER_AI_HISTORY_RETENTION_ENABLED=false` until an explicit AI history retention window is approved; then set `APP_LEDGER_AI_HISTORY_RETENTION_DAYS`, cron, and zone together.
- Keep `MINIO_STORAGE_CAPACITY_BYTES` positive in production if MinIO usage alerts should fire.
- Keep migration variables explicit even when Flyway is disabled so operators can intentionally turn it on per environment.
- Keep `APP_SCHEMA_LEGACY_UPDATERS_ENABLED=true` until a Flyway staging rehearsal needs to prove startup without legacy `*SchemaUpdater` assistance, then set it to `false` only with `DB_MIGRATION_ENABLED=true` and backup/restore evidence.

## Manual usage

```powershell
./scripts/verify-env-sync.ps1
./scripts/verify-env-sync.ps1 -EnvExamplePath .env.example
./scripts/verify-env-sync.ps1 -EnvExamplePath .env.example,.env.oci.app.example
```