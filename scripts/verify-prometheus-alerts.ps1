Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$rulesDir = 'deploy/oci/monitoring/prometheus/rules'
$prometheusConfigPath = 'deploy/oci/monitoring/prometheus/prometheus.yml'
$alertmanagerConfigPath = 'deploy/oci/monitoring/alertmanager/alertmanager.yml'
$monitoringComposePath = 'docker-compose.oci.monitoring.yml'
$observabilityDocPath = 'docs/observability_alerts.md'

$findings = [System.Collections.Generic.List[string]]::new()

if (-not (Test-Path -LiteralPath $rulesDir)) {
    $findings.Add("Missing Prometheus rules directory: $rulesDir") | Out-Null
}
if (-not (Test-Path -LiteralPath $prometheusConfigPath)) {
    $findings.Add("Missing Prometheus config: $prometheusConfigPath") | Out-Null
}
if (-not (Test-Path -LiteralPath $alertmanagerConfigPath)) {
    $findings.Add("Missing Alertmanager config: $alertmanagerConfigPath") | Out-Null
}
if (-not (Test-Path -LiteralPath $monitoringComposePath)) {
    $findings.Add("Missing monitoring compose file: $monitoringComposePath") | Out-Null
}
if (-not (Test-Path -LiteralPath $observabilityDocPath)) {
    $findings.Add("Missing observability alert document: $observabilityDocPath") | Out-Null
}

if ($findings.Count -eq 0) {
    $prometheusConfig = Get-Content -LiteralPath $prometheusConfigPath -Raw
    if ($prometheusConfig -notmatch '(?m)^\s*-\s*/etc/prometheus/rules/\*\.yml\s*$') {
        $findings.Add('Prometheus config must load /etc/prometheus/rules/*.yml') | Out-Null
    }
    if ($prometheusConfig -notmatch '(?s)alerting:\s*.*alertmanagers:\s*.*alertmanager:9093') {
        $findings.Add('Prometheus config must forward alerts to alertmanager:9093') | Out-Null
    }

    $monitoringCompose = Get-Content -LiteralPath $monitoringComposePath -Raw
    foreach ($snippet in @('alertmanager:', 'prom/alertmanager', '9093:9093', './deploy/oci/monitoring/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro')) {
        if (-not $monitoringCompose.Contains($snippet)) {
            $findings.Add("Monitoring compose missing Alertmanager snippet: $snippet") | Out-Null
        }
    }

    $alertmanagerConfig = Get-Content -LiteralPath $alertmanagerConfigPath -Raw
    foreach ($snippet in @('receiver: ops-null', 'receiver: ops-critical', 'receiver: ops-warning', 'severity="critical"', 'severity="warning"', 'inhibit_rules:')) {
        if (-not $alertmanagerConfig.Contains($snippet)) {
            $findings.Add("Alertmanager config missing routing snippet: $snippet") | Out-Null
        }
    }

    $document = Get-Content -LiteralPath $observabilityDocPath -Raw
    foreach ($snippet in @('Alertmanager routing baseline', 'Required alert coverage contract', 'Alert runbook contract', 'runbook_url', 'alertmanager:9093', 'ops-critical', 'ops-warning')) {
        if (-not $document.Contains($snippet)) {
            $findings.Add("Observability doc missing Alertmanager or coverage snippet: $snippet") | Out-Null
        }
    }

    $requiredCoverageAlerts = @(
        'CalenBackendHigh5xxRate',
        'CalenBackendSlowP95',
        'CalenLedgerOcrHighFailureRate',
        'CalenLedgerOcrSlowP95',
        'CalenLedgerAiHighFailureRate',
        'CalenLedgerAiSlowP95',
        'CalenExternalWorkflowHighFailureRate',
        'CalenExternalWorkflowSlowP95',
        'CalenDataOpsBackupFailure',
        'CalenDataOpsBackupStale',
        'CalenMinioStorageHighUsage',
        'CalenMinioCapacityMissing',
        'CalenHostDiskNearlyFull',
        'CalenRedisConnectionUnavailable',
        'CalenRedisStateConnectionUnavailable',
        'CalenHikariPoolNearlyExhausted',
        'CalenHikariPendingConnections',
        'CalenHikariConnectionTimeouts',
        'CalenLedgerAiHistoryRetentionFailure'
    )
    foreach ($alert in $requiredCoverageAlerts) {
        if (-not $document.Contains($alert)) {
            $findings.Add("Observability doc missing required coverage alert: $alert") | Out-Null
        }
    }

    $requiredCoveragePhrases = @(
        'API error rate',
        'API latency',
        'OCR failure and latency',
        'AI failure and latency',
        'n8n/external workflow health',
        'Backup success/failure',
        'MinIO capacity',
        'Redis availability',
        'DB pool exhaustion',
        'Privacy retention'
    )
    foreach ($phrase in $requiredCoveragePhrases) {
        if (-not $document.Contains($phrase)) {
            $findings.Add("Observability doc missing required coverage phrase: $phrase") | Out-Null
        }
    }

    $alertNames = @{}
    $ruleFiles = @(Get-ChildItem -LiteralPath $rulesDir -Filter '*.yml' -File)
    if ($ruleFiles.Count -eq 0) {
        $findings.Add("No Prometheus rule files found in $rulesDir") | Out-Null
    }

    foreach ($ruleFile in $ruleFiles) {
        $content = Get-Content -LiteralPath $ruleFile.FullName -Raw
        $matches = [regex]::Matches($content, '(?m)^\s*-\s*alert:\s*(?<name>[A-Za-z][A-Za-z0-9_]*)\s*$')
        if ($matches.Count -eq 0) {
            $findings.Add("$($ruleFile.FullName): no alert rules found") | Out-Null
            continue
        }

        for ($index = 0; $index -lt $matches.Count; $index += 1) {
            $match = $matches[$index]
            $name = $match.Groups['name'].Value
            $end = if ($index + 1 -lt $matches.Count) { $matches[$index + 1].Index } else { $content.Length }
            $block = $content.Substring($match.Index, $end - $match.Index)

            if ($alertNames.ContainsKey($name)) {
                $findings.Add("Duplicate alert name $name in $($ruleFile.FullName) and $($alertNames[$name])") | Out-Null
            } else {
                $alertNames[$name] = $ruleFile.FullName
            }

            if ($block -notmatch '(?m)^\s*expr:\s*(\||\S+)') {
                $findings.Add("${name}: missing expr") | Out-Null
            }
            if ($block -notmatch '(?m)^\s*for:\s*\S+') {
                $findings.Add("${name}: missing for duration") | Out-Null
            }
            if ($block -notmatch '(?m)^\s*severity:\s*(critical|warning|info)\s*$') {
                $findings.Add("${name}: missing bounded severity label") | Out-Null
            }
            if ($block -notmatch '(?m)^\s*summary:\s*\S+') {
                $findings.Add("${name}: missing summary annotation") | Out-Null
            }
            if ($block -notmatch '(?m)^\s*description:\s*\S+') {
                $findings.Add("${name}: missing description annotation") | Out-Null
            }
            if ($block -notmatch '(?m)^\s*runbook_url:\s*docs/observability_alerts\.md#alert-runbook-contract\s*$') {
                $findings.Add("${name}: missing runbook_url annotation") | Out-Null
            }
            if (-not $document.Contains($name)) {
                $findings.Add("${name}: alert is not documented in $observabilityDocPath") | Out-Null
            }
        }
    }

    foreach ($alert in $requiredCoverageAlerts) {
        if (-not $alertNames.ContainsKey($alert)) {
            $findings.Add("Required alert coverage rule is missing: $alert") | Out-Null
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'Prometheus alert verification failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Prometheus alert verification passed.'