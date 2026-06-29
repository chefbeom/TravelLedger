# Observability alerts

This project exposes Spring Actuator Prometheus metrics. The OCI monitoring stack loads Prometheus alert rules from:

```text
deploy/oci/monitoring/prometheus/rules/*.yml
```

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
| CalenRedisConnectionUnavailable | `calen_redis_connection_available == 0` for 5m | warning | Cache/state Redis is unavailable from the backend. |
| CalenMinioStorageHighUsage | MinIO used/capacity above 85% for 15m | warning | Object storage is nearing configured capacity. |
| CalenExternalWorkflowHighFailureRate | external workflow/client failure ratio above 10% for 10m | warning | n8n/OCR external calls are unreliable or unavailable. |
| CalenExternalWorkflowSlowP95 | external workflow/client p95 above 30s for 10m | warning | n8n/OCR external calls are approaching user-visible timeout territory. |
| CalenHostDiskNearlyFull | filesystem free space below 10% for 10m | critical | Uploads, backups, logs, and database files may fail. |

Prometheus evaluates these rules even before Alertmanager is introduced. Route them through Grafana alerting or add Alertmanager when notification channels are ready.

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
