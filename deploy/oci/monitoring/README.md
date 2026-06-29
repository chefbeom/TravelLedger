# Calen monitoring

This stack runs Prometheus, Alertmanager, and Grafana on the app server, then scrapes:

- the Calen backend Prometheus endpoint at `backend:8080/actuator/prometheus`
- the app server node_exporter at `host.docker.internal:9100`
- the data server node_exporter at `10.0.0.20:9100`

## Before starting

Check that node_exporter is reachable.

On each server:

```sh
curl -fsS http://127.0.0.1:9100/metrics | head
```

From the app server to the data server:

```sh
curl -fsS http://10.0.0.20:9100/metrics | head
```

If the data server private IP is not `10.0.0.20`, edit:

```text
deploy/oci/monitoring/prometheus/file_sd/node-exporters.yml
```

The app server target defaults to `host.docker.internal:9100` because Prometheus
runs inside Docker. If that target is down, replace it with the app server
private IP and keep port `9100` reachable from the Prometheus container.

## Start

From the repository root on the app server:

```sh
docker compose -f docker-compose.oci.app.yml -f docker-compose.oci.monitoring.yml up -d
```

Prometheus is bound to `127.0.0.1:9090` by default.
Alertmanager is bound to `127.0.0.1:9093` by default.
Grafana is bound to `127.0.0.1:3000` by default.

For remote access, use an SSH tunnel:

```sh
ssh -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 -L 9093:127.0.0.1:9093 ubuntu@APP_SERVER_PUBLIC_IP
```

Then open:

- Grafana: `http://127.0.0.1:3000`
- Prometheus: `http://127.0.0.1:9090`
- Alertmanager: `http://127.0.0.1:9093`

Set `GRAFANA_ADMIN_PASSWORD` before first start.

## Alerts

Prometheus loads alert rules from:

```text
deploy/oci/monitoring/prometheus/rules/*.yml
```

Prometheus forwards firing and resolved alerts to Alertmanager at `alertmanager:9093`. The default Alertmanager file is intentionally safe: it groups alerts, separates `critical` and `warning` routes, and uses no-op receivers so development stacks do not send external notifications.

Before production use, replace the no-op receivers in:

```text
deploy/oci/monitoring/alertmanager/alertmanager.yml
```

Use a deployment-specific mounted config or secret manager for Slack, webhook, email, or incident-management credentials. Do not commit webhook URLs or API keys.

The default rules cover backend availability, HTTP 5xx ratio, p95 latency, Hikari pool pressure, JVM heap pressure, node_exporter availability, AI/OCR and n8n/external workflow failures, backup failures/staleness, Redis availability, MinIO storage usage, and host disk capacity. See `docs/observability_alerts.md` for the full rule list and the metric contract.

After editing rules or Alertmanager routes, restart or reload the monitoring stack:

```sh
docker compose -f docker-compose.oci.app.yml -f docker-compose.oci.monitoring.yml restart prometheus alertmanager
```

## Dashboard imports

Grafana is pre-provisioned with a `Prometheus` datasource.

Import these dashboard IDs and select the `Prometheus` datasource:

- `1860` - Node Exporter Full
- `4701` - JVM Micrometer
- `12900` - SpringBoot APM Dashboard
- `15798` - Docker Monitoring

## Network notes

Keep `:9100` reachable only over the private network or from the app server.
The data server firewall/security list should allow `APP_SERVER_PRIVATE_IP`
to reach `DATA_SERVER_PRIVATE_IP:9100`.
Do not expose Prometheus, Alertmanager, or Grafana publicly unless they are behind proper auth and firewall rules.