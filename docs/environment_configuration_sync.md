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
- `OPS_CONTROL_SEAL_KEY` must be supplied to every backend Compose service and must remain stable across redeploys. It is the envelope-encryption key for administrator AI credentials stored in `admin_ops_control_settings`; keep it in OCI Vault or an equivalent root-only secret source, never in Git.
- If an older deployment stored administrator AI credentials while `OPS_CONTROL_SEAL_KEY` was omitted, the backend can read ciphertext created from the legacy `JWT_KEY` during migration. Keep the old `JWT_KEY` available for the first rollout, then use the dedicated seal key for all new writes and rotate it only through an explicit re-encryption procedure.
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