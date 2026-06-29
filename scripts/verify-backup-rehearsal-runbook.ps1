Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runbookPath = 'docs/backup_restore_rehearsal_runbook.md'
if (-not (Test-Path -LiteralPath $runbookPath)) {
    throw "Backup restore rehearsal runbook not found: $runbookPath"
}

$content = Get-Content -LiteralPath $runbookPath -Raw
$findings = [System.Collections.Generic.List[string]]::new()

$requiredSections = @(
    '# Backup Restore Rehearsal Runbook',
    '## Goal',
    '## Scope',
    '## Rehearsal Cadence',
    '## Preflight Checklist',
    '## DB Restore Rehearsal Procedure',
    '## MinIO Restore Rehearsal Procedure',
    '## Encryption and Secret Handling',
    '## Evidence Template',
    '## Failure Handling',
    '## Release Gate'
)

foreach ($section in $requiredSections) {
    if (-not $content.Contains($section)) {
        $findings.Add("Missing required runbook section: $section") | Out-Null
    }
}

$requiredEvidenceFields = @(
    'Rehearsal date/time:',
    'Operator:',
    'Environment:',
    'Backup type: DB | MinIO | both',
    'Remote name:',
    'Remote directory:',
    'Artifact name:',
    'Artifact timestamp:',
    'Artifact size:',
    'Restore target:',
    'Restore started at:',
    'Restore finished at:',
    'Smoke checks:',
    'Alerts checked:',
    'Cleanup completed:',
    'Production data touched: no | yes, explain',
    'Result: pass | fail',
    'Follow-up issues:'
)

foreach ($field in $requiredEvidenceFields) {
    if (-not $content.Contains($field)) {
        $findings.Add("Missing required evidence template field: $field") | Out-Null
    }
}

$requiredSafetyPhrases = @(
    'Do not paste `.env`, `rclone.conf`, DB passwords, OAuth tokens, or signed URLs into evidence.',
    'Prefer encrypted backup artifacts before storing outside the server trust boundary.',
    'If encryption is enabled, rehearse decrypt-and-restore, not only download.',
    'Store encryption keys outside the backup remote.',
    'A successful rehearsal record using this runbook.',
    'A named fallback artifact created before risky production work.'
)

foreach ($phrase in $requiredSafetyPhrases) {
    if (-not $content.Contains($phrase)) {
        $findings.Add("Missing required backup safety phrase: $phrase") | Out-Null
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'Backup restore rehearsal runbook check failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Backup restore rehearsal runbook check passed.'