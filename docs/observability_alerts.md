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
| CalenRedisConnectionUnavailable | `calen_redis_connection_available == 0` for 5m | warning | Cache/state Redis is unavailable from the backend. |
| CalenMinioStorageHighUsage | MinIO used/capacity above 85% for 15m | warning | Object storage is nearing configured capacity. |
| CalenExternalWorkflowHighFailureRate | external workflow/client failure ratio above 10% for 10m | warning | n8n/OCR external calls are unreliable or unavailable. |
| CalenExternalWorkflowSlowP95 | external workflow/client p95 above 30s for 10m | warning | n8n/OCR external calls are approaching user-visible timeout territory. |
| CalenHostDiskNearlyFull | filesystem free space below 10% for 10m | critical | Uploads, backups, logs, and database files may fail. |

## Required alert coverage contract

| Objective area | Required alert coverage | Operator action |
| --- | --- | --- |
| API error rate | `CalenBackendHigh5xxRate` | Check recent deployments, backend logs, upstream DB/Redis errors, and rollback if the error budget is burning quickly. |
| API latency | `CalenBackendSlowP95` | Check slow endpoints, DB pool saturation, Redis latency, object storage latency, and external workflow calls. |
| OCR failure and latency | `CalenLedgerOcrHighFailureRate`, `CalenLedgerOcrSlowP95` | Verify OCR provider health, request timeouts, upload constraints, and user-visible retry behavior. |
| AI failure and latency | `CalenLedgerAiHighFailureRate`, `CalenLedgerAiSlowP95` | Verify LM Studio/n8n availability, provider timeout budget, schema validation failures, and advisory-only UI copy. |
| n8n/external workflow health | `CalenExternalWorkflowHighFailureRate`, `CalenExternalWorkflowSlowP95` | Check n8n workflow executions, webhook credentials, network reachability, and bounded retry behavior. |
| Backup success/failure | `CalenDataOpsBackupFailure`, `CalenDataOpsBackupStale` | Inspect DB/MinIO backup logs, confirm last successful artifact, and run the restore rehearsal checklist when needed. |
| MinIO capacity | `CalenMinioStorageHighUsage`, `CalenHostDiskNearlyFull` | Check bucket capacity configuration, object growth, lifecycle cleanup, and host filesystem space. |
| Redis availability | `CalenRedisConnectionUnavailable` | Check Redis process/network health and whether cache/state fallback behavior is safe. |
| DB pool exhaustion | `CalenHikariPoolNearlyExhausted`, `CalenHikariPendingConnections`, `CalenHikariConnectionTimeouts` | Inspect slow queries, connection leaks, pool size, DB health, and recent traffic spikes. |
| Privacy retention | `CalenLedgerAiHistoryRetentionFailure` | Check scheduled cleanup logs before relying on AI history retention guarantees. |

`scripts/verify-prometheus-alerts.ps1` treats this table as the minimum coverage contract. Removing or renaming one of these alerts should be a deliberate release decision with a replacement alert and updated operator action.

## Alertmanager routing baseline

| Route | Receiver | Repeat interval | Intended channel |
| --- | --- | --- | --- |
| `severity="critical"` | `ops-critical` | 1h | Pager/urgent chat after production channel is configured. |
| `severity="warning"` | `ops-warning` | 4h | Team chat or issue queue after production channel is configured. |
| fallback | `ops-null` | 4h | Safe default for development and unclassified alerts. |

Before production use, replace the no-op `ops-critical` and `ops-warning` receivers in `deploy/oci/monitoring/alertmanager/alertmanager.yml` with approved Slack, webhook, email, or incident-management integrations. Do not put API keys or webhook secrets directly in Git; mount a generated local config or inject secrets through the deployment secret manager.

## Verification gate

`scripts/verify-prometheus-alerts.ps1` checks that Prometheus loads `/etc/prometheus/rules/*.yml`, forwards alerts to `alertmanager:9093`, the monitoring compose stack runs Alertmanager, Alertmanager has critical/warning routes, every alert has an expression, duration, bounded severity, summary, and description, every alert name is documented here, and the Required alert coverage contract remains present. The GitHub Actions `observability-alerts` job runs this gate on push and pull request.

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
| MinIO storage capacity bytes | `calen_minio_storage_capacity_bytes` | `calen.minio.storage.capacity.bytes` | `bucket` | `MinioBackupArchiveService` |
| MinIO object count | `calen_minio_storage_objects` | `calen.minio.storage.objects` | `bucket` | `MinioBackupArchiveService` |
| External workflow request count | `calen_external_workflow_requests_total` | `calen.external.workflow.requests` | `workflow`, `status` | `LedgerAiN8nClient`, `LedgerOcrRemoteClient` |
| External workflow request duration | `calen_external_workflow_request_seconds_bucket` | `calen.external.workflow.request` | `workflow`, `status` | `LedgerAiN8nClient`, `LedgerOcrRemoteClient` |
| Ledger AI history retention run count | `calen_ledger_ai_history_retention_runs_total` | `calen.ledger.ai.history.retention.runs` | `status` | `LedgerAiAnalysisHistoryRetentionService` |

Status labels are intentionally bounded. They must not include user IDs, tokens, filenames, prompts, IP addresses, or provider error bodies.

## Platform metrics used by alerts

| Area | Prometheus metric | Provider | Labels | Alert |
| --- | --- | --- | --- | --- |
| DB pool saturation | `hikaricp_connections_active`, `hikaricp_connections_max` | Spring Boot Actuator / Micrometer HikariCP | `pool` | `CalenHikariPoolNearlyExhausted` |
| DB pool waiting requests | `hikaricp_connections_pending` | Spring Boot Actuator / Micrometer HikariCP | `pool` | `CalenHikariPendingConnections` |
| DB connection acquisition timeout | `hikaricp_connections_timeout_total` | Spring Boot Actuator / Micrometer HikariCP | `pool` | `CalenHikariConnectionTimeouts` |

## Remaining metric contract

No remaining metric contract from this alert pass is pending implementation.
Implementation notes:

- Keep `MINIO_STORAGE_CAPACITY_BYTES` set to a positive value in production to enable usage-ratio alerts.
- Keep labels bounded and operational. Avoid user-controlled or high-cardinality values.
- Record status values such as `success`, `failure`, `timeout`, `invalid`, `expired`, `revoked`, and `limit_reached`.
- Alert annotations should describe operational action, not expose private request data.
- Keep Alertmanager receiver secrets out of Git; use deployment-specific mounted config for real notification channels.