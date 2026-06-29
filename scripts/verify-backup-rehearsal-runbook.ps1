Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$runbookPath = 'docs/backup_restore_rehearsal_runbook.md'
$backupScriptPath = 'deploy/oci/scripts/backup-to-gdrive.sh'
$backupSetupPath = 'docs/dbtogdrive.md'
$restoreGuidePath = 'docs/db_restore_from_gdrive.md'
$dataEnvPath = '.env.oci.data.example'

foreach ($path in @($runbookPath, $backupScriptPath, $backupSetupPath, $restoreGuidePath, $dataEnvPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required backup artifact not found: $path"
    }
}

$content = Get-Content -LiteralPath $runbookPath -Raw
$backupScript = Get-Content -LiteralPath $backupScriptPath -Raw
$backupSetup = Get-Content -LiteralPath $backupSetupPath -Raw
$restoreGuide = Get-Content -LiteralPath $restoreGuidePath -Raw
$dataEnv = Get-Content -LiteralPath $dataEnvPath -Raw
$findings = [System.Collections.Generic.List[string]]::new()

function Assert-Contains {
    param(
        [string]$Label,
        [string]$Content,
        [string]$Needle
    )
    if (-not $Content.Contains($Needle)) {
        $findings.Add("$Label is missing required text: $Needle") | Out-Null
    }
}

$requiredSections = @(
    '# Backup Restore Rehearsal Runbook',
    '## Goal',
    '## Scope',
    '## Rehearsal Cadence',
    '## Preflight Checklist',
    '## DB Restore Rehearsal Procedure',
    '## MinIO Restore Rehearsal Procedure',
    '## Encryption and Secret Handling',
    '## Encryption Acceptance Matrix',
    '## Script Encryption Options',
    '## Evidence Template',
    '## Failure Handling',
    '## Release Gate'
)

foreach ($section in $requiredSections) {
    Assert-Contains -Label 'Backup runbook' -Content $content -Needle $section
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
    'Checksum file:',
    'Checksum verification result:',
    'Encryption status: encrypted | exception-approved | not-applicable-local',
    'Backup script encryption mode: none | age | gpg',
    'Encryption tool or key alias:',
    'Key owner/recovery owner:',
    'Decrypt test result:',
    'Integrity check: checksum | manifest | table counts | sample object compare',
    'Plaintext exception owner/expiry:',
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
    Assert-Contains -Label 'Backup runbook evidence template' -Content $content -Needle $field
}

$requiredSafetyPhrases = @(
    'Do not paste `.env`, `rclone.conf`, DB passwords, OAuth tokens, or signed URLs into evidence.',
    'Prefer encrypted backup artifacts before storing outside the server trust boundary.',
    'If encryption is enabled, rehearse decrypt-and-restore, not only download.',
    'Store encryption keys outside the backup remote.',
    'BACKUP_ENCRYPTION_MODE',
    'BACKUP_AGE_RECIPIENTS',
    'BACKUP_GPG_RECIPIENT',
    'KEEP_PLAINTEXT_AFTER_ENCRYPTION',
    'The script also uploads a `.sha256` sidecar',
    'A successful rehearsal record using this runbook.',
    'A named fallback artifact created before risky production work.',
    'Encryption evidence for DB dumps, MinIO archives, and fallback dumps, including decrypt and integrity-check results.',
    'A plaintext exception owner, expiry date, compensating control, and follow-up issue if any backup artifact remains unencrypted.'
)

foreach ($phrase in $requiredSafetyPhrases) {
    Assert-Contains -Label 'Backup runbook safety contract' -Content $content -Needle $phrase
}

$requiredScriptPhrases = @(
    'BACKUP_ENCRYPTION_MODE="${BACKUP_ENCRYPTION_MODE:-none}"',
    'BACKUP_AGE_RECIPIENTS="${BACKUP_AGE_RECIPIENTS:-}"',
    'BACKUP_GPG_RECIPIENT="${BACKUP_GPG_RECIPIENT:-}"',
    'KEEP_PLAINTEXT_AFTER_ENCRYPTION="${KEEP_PLAINTEXT_AFTER_ENCRYPTION:-false}"',
    'encrypt_backup_if_requested',
    'BACKUP_ENCRYPTION_MODE=age requires BACKUP_AGE_RECIPIENTS.',
    'BACKUP_ENCRYPTION_MODE=gpg requires BACKUP_GPG_RECIPIENT.',
    'age "${age_args[@]}" -o "$ENCRYPTED_FILE_PATH" "$FILE_PATH"',
    'gpg --batch --yes --trust-model always --recipient "$BACKUP_GPG_RECIPIENT"',
    'sha256sum "$source_name" > "${source_name}.sha256"',
    'rclone --config "$RCLONE_CONFIG" copyto "$CHECKSUM_FILE_PATH"',
    'rm -f "$FILE_PATH" "$UPLOAD_FILE_PATH" "$CHECKSUM_FILE_PATH"'
)

foreach ($phrase in $requiredScriptPhrases) {
    Assert-Contains -Label 'backup-to-gdrive.sh' -Content $backupScript -Needle $phrase
}

$requiredSetupPhrases = @(
    '### 11-3. 백업 스크립트 암호화 옵션',
    'BACKUP_ENCRYPTION_MODE=age',
    'BACKUP_AGE_RECIPIENTS=age1...',
    'BACKUP_ENCRYPTION_MODE=gpg',
    'BACKUP_GPG_RECIPIENT=backup-ops@example.com',
    '.sha256 sidecar'
)
foreach ($phrase in $requiredSetupPhrases) {
    Assert-Contains -Label 'DB backup setup guide' -Content $backupSetup -Needle $phrase
}

$requiredRestorePhrases = @(
    '## 12. 암호화된 백업 복구',
    'sha256sum -c calen-2026-03-29-000000.sql.gz.age.sha256',
    'age -d -i /secure/keys/calen-backup-age.txt',
    'sha256sum -c calen-2026-03-29-000000.sql.gz.gpg.sha256',
    'gpg --batch --decrypt --output',
    '키 별칭 또는 fingerprint만 남기고 실제 키 자료는 남기지 않습니다.'
)
foreach ($phrase in $requiredRestorePhrases) {
    Assert-Contains -Label 'DB restore guide' -Content $restoreGuide -Needle $phrase
}

$requiredEnvPhrases = @(
    'BACKUP_ENCRYPTION_MODE=none',
    'BACKUP_AGE_RECIPIENTS=',
    'BACKUP_GPG_RECIPIENT=',
    'KEEP_PLAINTEXT_AFTER_ENCRYPTION=false'
)
foreach ($phrase in $requiredEnvPhrases) {
    Assert-Contains -Label '.env.oci.data.example' -Content $dataEnv -Needle $phrase
}

if ($findings.Count -gt 0) {
    Write-Host 'Backup restore rehearsal runbook check failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Backup restore rehearsal runbook check passed.'
