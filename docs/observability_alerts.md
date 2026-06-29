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
| CalenJvmHeapHigh | JVM heap above 90% for 10m | warning | The backend may be heading toward GC pressure/OOM. |
| CalenLedgerAiHighFailureRate | AI remote failure ratio above 10% for 10m | warning | LM Studio/n8n analysis is unreliable or unavailable. |
| CalenLedgerAiSlowP95 | AI remote p95 latency above 90s for 10m | warning | AI analysis is close to user-visible timeout territory. |
| CalenLedgerOcrHighFailureRate | OCR failure ratio above 10% for 10m | warning | Receipt/image analysis is unreliable or unavailable. |
| CalenLedgerOcrSlowP95 | OCR p95 latency above 60s for 10m | warning | OCR analysis is close to user-visible timeout territory. |
| CalenPublicDownloadLinkInvalidSpike | invalid/unavailable public-link attempts above 3 per minute for 10m | warning | Shared links may be under abuse, stale, or frequently misused. |
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

Status labels are intentionally bounded. They must not include user IDs, tokens, filenames, prompts, IP addresses, or provider error bodies.

## Remaining metric contract

The following alerts are part of the operational target but still require application/exporter metrics.

| Area | Proposed metric | Labels | Alert condition |
| --- | --- | --- | --- |
| n8n workflow client | `calen_external_workflow_request_seconds_bucket` | `workflow`, `status` | p95 above 30s or failures above 10% |
| Backup | `calen_data_ops_backup_runs_total` | `type`, `status` | no success in 26h or any failed run |
| MinIO | `calen_minio_storage_used_bytes` / `calen_minio_storage_capacity_bytes` | `bucket` | usage above 85% |
| Redis | `calen_redis_connection_available` | `role` | value is 0 for 5m |

Implementation notes:

- Use Micrometer `Counter`, `Timer`, and `Gauge` in backup, Redis, and MinIO services.
- Keep labels bounded and operational. Avoid user-controlled or high-cardinality values.
- Record status values such as `success`, `failure`, `timeout`, `invalid`, `expired`, `revoked`, and `limit_reached`.
- Alert annotations should describe operational action, not expose private request data.
