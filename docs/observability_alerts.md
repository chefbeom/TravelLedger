# Observability alerts

This project exposes Spring Actuator Prometheus metrics. The OCI monitoring stack loads Prometheus alert rules from:

```text
deploy/oci/monitoring/prometheus/rules/*.yml
```

Prometheus sends firing and resolved alerts to Alertmanager at `alertmanager:9093`. The checked-in Alertmanager baseline groups alerts by `alertname`, `service`, and `feature`, routes `critical` and `warning` severities separately, and uses no-op receivers until production notification channels are attached.

## Active Prometheus alerts

| Alert | Signal | Severity | Why it matters |
| --- | --- | --- | --- |
| CalenBackendDown | `up{job="calen-backend"} == 0` | critical | Backend metrics endpoint is not reachable. |
| CalenNodeExporterDown | `up{job="node-exporter"} == 0` | warning | Host metrics are missing for app/data capacity checks. |
| CalenBackendHigh5xxRate | 5xx ratio above 5% for 5m | critical | Users are seeing server-side failures. |
| CalenBackendSlowP95 | p95 HTTP latency above 2s for 10m | warning | API responsiveness is degrading. |
| CalenHikariPoolNearlyExhausted | Hikari active/max above 90% for 5m | critical | DB connection starvation is likely. |
| CalenHikariPendingConnections | Hikari pending connections above 0 for 2m | critical | Requests are already waiting for DB pool capacity. |
| CalenHikariConnectionTimeouts | Hikari acquisition timeouts above 0 within 5m | critical | Requests have failed to acquire DB connections. |
| CalenJvmHeapHigh | JVM heap above 90% for 10m | warning | The backend may be heading toward GC pressure/OOM. |
| CalenLedgerAiHighFailureRate | AI remote failure ratio above 10% for 10m | warning | LM Studio/n8n analysis is unreliable or unavailable. |
| CalenLedgerAiSlowP95 | AI remote p95 latency above 90s for 10m | warning | AI analysis is close to user-visible timeout territory. |
| CalenLedgerOcrHighFailureRate | OCR failure ratio above 10% for 10m | warning | Receipt/image analysis is unreliable or unavailable. |
| CalenLedgerOcrSlowP95 | OCR p95 latency above 60s for 10m | warning | OCR analysis is close to user-visible timeout territory. |
| CalenPublicDownloadLinkInvalidSpike | invalid/unavailable public-link attempts above 3 per minute for 10m | warning | Shared links may be under abuse, stale, or frequently misused. |
| CalenDataOpsBackupFailure | backup failure count above 0 within 30m | critical | DB or MinIO backup has failed and needs operator action. |
| CalenDataOpsBackupStale | no recorded successful backup within 26h | warning | Scheduled backup may be disabled, stuck, or failing before completion. |
| CalenLedgerAiHistoryRetentionFailure | AI history retention failure count above 0 within 30m | warning | Automatic cleanup failed, so privacy retention guarantees may not be holding. |
| CalenRedisConnectionUnavailable | cache Redis `calen_redis_connection_available == 0` for 5m | warning | Cache Redis is unavailable from the backend. |
| CalenRedisStateConnectionUnavailable | state Redis `calen_redis_connection_available == 0` for 2m | critical | State Redis is unavailable, so session state, throttling, locks, and backup coordination may be unsafe. |
| CalenMinioStorageHighUsage | MinIO used/capacity above 85% for 15m | warning | Object storage is nearing configured capacity. |
| CalenMinioCapacityMissing | MinIO capacity metric is missing or not positive for 30m | warning | Usage-ratio alerts cannot evaluate safely without capacity metric/configuration. |
| CalenExternalWorkflowHighFailureRate | external workflow/client failure ratio above 10% for 10m | warning | n8n/OCR external calls are unreliable or unavailable. |
| CalenExternalWorkflowSlowP95 | external workflow/client p95 above 30s for 10m | warning | n8n/OCR external calls are approaching user-visible timeout territory. |
| CalenN8nWorkflowHighFailureRate | n8n/workflow failure ratio above 10% for 10m | warning | Isolates n8n-backed and workflow-backed failures from direct LM Studio/OCR client calls. |
| CalenN8nWorkflowSlowP95 | n8n/workflow p95 above 30s for 10m | warning | Tracks n8n response time separately so workflow latency is not hidden inside generic provider latency. |
| CalenHostDiskNearlyFull | filesystem free space below 10% for 10m | critical | Uploads, backups, logs, and database files may fail. |

## Required alert coverage contract

| Objective area | Required alert coverage | Operator action |
| --- | --- | --- |
| API error rate | `CalenBackendHigh5xxRate` | Check recent deployments, backend logs, upstream DB/Redis errors, and rollback if the error budget is burning quickly. |
| API latency | `CalenBackendSlowP95` | Check slow endpoints, DB pool saturation, Redis latency, object storage latency, and external workflow calls. |
| OCR failure and latency | `CalenLedgerOcrHighFailureRate`, `CalenLedgerOcrSlowP95` | Verify OCR provider health, request timeouts, upload constraints, and user-visible retry behavior. |
| AI failure and latency | `CalenLedgerAiHighFailureRate`, `CalenLedgerAiSlowP95` | Verify LM Studio/n8n availability, provider timeout budget, schema validation failures, and advisory-only UI copy. |
| n8n response time and failure | `CalenN8nWorkflowHighFailureRate`, `CalenN8nWorkflowSlowP95`, `CalenExternalWorkflowHighFailureRate`, `CalenExternalWorkflowSlowP95` | Check n8n workflow executions, webhook credentials, network reachability, and bounded retry behavior. Use the n8n-specific alerts first when the `workflow` label contains `n8n` or `workflow`; use the generic external-workflow alerts for direct LM Studio/OCR client degradation. |
| Backup success/failure | `CalenDataOpsBackupFailure`, `CalenDataOpsBackupStale` | Inspect DB/MinIO backup logs, confirm last successful artifact, and run the restore rehearsal checklist when needed. |
| MinIO capacity | `CalenMinioStorageHighUsage`, `CalenMinioCapacityMissing`, `CalenHostDiskNearlyFull` | Check bucket capacity configuration, object growth, lifecycle cleanup, and host filesystem space; set a positive `MINIO_STORAGE_CAPACITY_BYTES` before trusting usage-ratio alerts. |
| Redis availability | `CalenRedisConnectionUnavailable`, `CalenRedisStateConnectionUnavailable` | Check Redis process/network health, distinguish cache from state Redis, and verify session, throttling, locking, and backup-coordination fallback behavior before declaring recovery. |
| DB pool exhaustion | `CalenHikariPoolNearlyExhausted`, `CalenHikariPendingConnections`, `CalenHikariConnectionTimeouts` | Inspect slow queries, connection leaks, pool size, DB health, and recent traffic spikes. |
| Privacy retention | `CalenLedgerAiHistoryRetentionFailure` | Check scheduled cleanup logs before relying on AI history retention guarantees. |

`scripts/verify-prometheus-alerts.ps1` treats this table as the minimum coverage contract. Removing or renaming one of these alerts should be a deliberate release decision with a replacement alert and updated operator action.

## Alert runbook contract

Every checked-in alert must include a `runbook_url` annotation that points to this document. Alert text and linked runbooks are allowed to describe systems, metrics, and bounded status labels only; they must not include request bodies, user identifiers, filenames, prompts, provider responses, public tokens, presigned URLs, API keys, webhook URLs, or raw exception payloads.

| First step | Required evidence | Escalate when |
| --- | --- | --- |
| Confirm scope in Prometheus and Alertmanager. | Alert name, severity, service, feature, firing duration, affected target, and latest value. | A `critical` alert fires for more than one evaluation interval or multiple services fire together. |
| Check the closest owned system first. | Backend logs for app alerts, provider/workflow execution logs for AI/OCR/n8n alerts, backup logs for data-ops alerts, node metrics for disk/host alerts. | The closest system is healthy but the alert keeps firing. |
| Preserve user safety boundaries while debugging. | Screenshots or notes redact secrets, user IDs, filenames, prompts, provider responses, public tokens, presigned URLs, and raw EXIF/GPS data. | Any diagnostic evidence would require sensitive data exposure. |
| Record the outcome. | Incident note links alert name, time window, root cause, mitigation, and follow-up issue/PR. | A workaround remains active or alert thresholds had to be changed. |

Runbook expectations by alert family:

| Alert family | Start here | Safe remediation direction |
| --- | --- | --- |
| Backend SLO and availability | Recent deploys, backend logs, `/actuator/health`, DB/Redis reachability, and error-budget burn. | Roll back unsafe deploys, shed expensive calls, or scale backend only after dependency health is clear. |
| DB pool pressure | Slow query logs, Hikari pending/timeout metrics, connection leak candidates, and traffic spikes. | Fix query/leak cause before increasing pool size; resizing without root cause can move failure to MariaDB. |
| AI/OCR/n8n/external workflow | Provider health, timeout budget, workflow executions, schema-validation failures, and retry/duplicate-suppression behavior. | Keep results advisory-only; disable provider or workflow integration before allowing duplicate or unsafe writes. |
| Backup and retention | Backup job logs, latest artifact/checksum, encryption/decrypt evidence, and retention cleanup logs. | Run the restore rehearsal checklist before declaring backup recovery healthy. |
| Redis, MinIO, and host capacity | Redis connection metrics by `role`, bucket usage/capacity, capacity missing/non-positive alerts, lifecycle cleanup, disk usage by mount, and upload growth. | Treat `role="state"` Redis loss as critical because locks, throttling, backup coordination, and temporary auth state may be unsafe; prefer cleanup/lifecycle/capacity fixes before reducing retention or disabling safety checks. |
| Public-link abuse | Access-log status mix, token expiry/revocation settings, rate-limit evidence, and owner-scoped audit logs. | Revoke affected links and preserve token fingerprints only; never log raw public tokens. |
## Alertmanager routing baseline

| Route | Receiver | Repeat interval | Intended channel |
| --- | --- | --- | --- |
| `severity="critical"` | `ops-critical` | 1h | Pager/urgent chat after production channel is configured. |
| `severity="warning"` | `ops-warning` | 4h | Team chat or issue queue after production channel is configured. |
| fallback | `ops-null` | 4h | Safe default for development and unclassified alerts. |

Before production use, replace the no-op `ops-critical` and `ops-warning` receivers in `deploy/oci/monitoring/alertmanager/alertmanager.yml` with approved Slack, webhook, email, or incident-management integrations. Do not put API keys or webhook secrets directly in Git; mount a generated local config or inject secrets through the deployment secret manager.

## Verification gate

`scripts/verify-prometheus-alerts.ps1` checks that Prometheus loads `/etc/prometheus/rules/*.yml`, forwards alerts to `alertmanager:9093`, the monitoring compose stack runs Alertmanager, Alertmanager has critical/warning routes, every alert has an expression, duration, bounded severity, summary, description, and `runbook_url`, every alert name is documented here, and the Required alert coverage contract plus runbook contract remain present. The GitHub Actions `observability-alerts` job runs this gate on push and pull request.

## Implemented application metrics

| Area | Prometheus metric | Micrometer source name | Labels | Source |
| --- | --- | --- | --- | --- |
| Ledger AI request count | `calen_ledger_ai_requests_total` | `calen.ledger.ai.requests` | `provider`, `status` | `LedgerAiAnalysisService` |
| Ledger AI request duration | `calen_ledger_ai_request_seconds_bucket` | `calen.ledger.ai.request` | `provider`, `status` | `LedgerAiAnalysisService` |
| Ledger OCR request count | `calen_ledger_ocr_requests_total` | `calen.ledger.ocr.requests` | `status`, `reason` | `LedgerOcrService` |
| Ledger OCR request duration | `calen_ledger_ocr_request_seconds_bucket` | `calen.ledger.ocr.request` | `status`, `reason` | `LedgerOcrService` |
| Public download link request count | `calen_public_download_link_requests_total` | `calen.public.download.link.requests` | `status` | `DriveDownloadLinkService` |
| Data ops backup run count | `calen_data_ops_backup_runs_total` | `calen.data.ops.backup.runs` | `type`, `status` | `AdminDataManagementService` |
| Data ops backup last success | `calen_data_ops_backup_last_success_timestamp` | `calen.data.ops.backup.last.success.timestamp` | `type` | `AdminDataManagementService` |
| Redis connection availability | `calen_redis_connection_available` | `calen.redis.connection.available` | `role` | `RedisCacheService`, `RedisStateService` |
| MinIO storage used bytes | `calen_minio_storage_used_bytes` | `calen.minio.storage.used.bytes` | `bucket` | `MinioBackupArchiveService` |
| MinIO storage capacity bytes | `calen_minio_storage_capacity_bytes` | `calen.minio.storage.capacity.bytes` | `bucket` | `MinioBackupArchiveService`; also drives `CalenMinioCapacityMissing` when not positive |
| MinIO object count | `calen_minio_storage_objects` | `calen.minio.storage.objects` | `bucket` | `MinioBackupArchiveService` |
| External workflow request count | `calen_external_workflow_requests_total` | `calen.external.workflow.requests` | `workflow`, `status` | `LedgerAiN8nClient`, `LedgerOcrRemoteClient` |
| External workflow request duration | `calen_external_workflow_request_seconds_bucket` | `calen.external.workflow.request` | `workflow`, `status` | `LedgerAiN8nClient`, `LedgerOcrRemoteClient` |
| Ledger AI history retention run count | `calen_ledger_ai_history_retention_runs_total` | `calen.ledger.ai.history.retention.runs` | `status` | `LedgerAiAnalysisHistoryRetentionService` |

Status labels are intentionally bounded. They must not include user IDs, tokens, filenames, prompts, IP addresses, or provider error bodies.

n8n-specific alerts match bounded workflow labels containing 
8n or workflow, such as ledger-ai-n8n and ledger-ocr-workflow. Direct clients such as ledger-ai-lmstudio and ledger-ocr-direct remain covered by the generic external workflow/client alerts.

## Platform metrics used by alerts

| Area | Prometheus metric | Provider | Labels | Alert |
| --- | --- | --- | --- | --- |
| DB pool saturation | `hikaricp_connections_active`, `hikaricp_connections_max` | Spring Boot Actuator / Micrometer HikariCP | `pool` | `CalenHikariPoolNearlyExhausted` |
| DB pool waiting requests | `hikaricp_connections_pending` | Spring Boot Actuator / Micrometer HikariCP | `pool` | `CalenHikariPendingConnections` |
| DB connection acquisition timeout | `hikaricp_connections_timeout_total` | Spring Boot Actuator / Micrometer HikariCP | `pool` | `CalenHikariConnectionTimeouts` |

## Remaining metric contract

No remaining metric contract from this alert pass is pending implementation.
Implementation notes:

- Keep `MINIO_STORAGE_CAPACITY_BYTES` set to a positive value in production; `CalenMinioCapacityMissing` fires when capacity is missing or non-positive so usage-ratio alerts are not silently disabled.
- Keep labels bounded and operational. Avoid user-controlled or high-cardinality values.
- Record status values such as `success`, `failure`, `timeout`, `invalid`, `expired`, `revoked`, and `limit_reached`.
- Alert annotations should describe operational action, include `runbook_url`, and not expose private request data.
- Keep Alertmanager receiver secrets out of Git; use deployment-specific mounted config for real notification channels.