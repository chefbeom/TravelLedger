Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$contractPath = 'docs/admin_audit_log_contract.md'
$securityChecklistPath = 'docs/security_baseline_checklist.md'
$ciPath = '.github/workflows/ci.yml'
$loginAuditServicePath = 'backend/src/main/java/com/playdata/calen/account/service/LoginAuditLogService.java'
$adminControllerPath = 'backend/src/main/java/com/playdata/calen/account/web/AdminController.java'
$driveAdminControllerPath = 'backend/src/main/java/com/playdata/calen/drive/web/DriveAdminController.java'
$loginAuditTestPath = 'backend/src/test/java/com/playdata/calen/account/service/LoginAuditLogServiceTest.java'
$driveAdminSecurityTestPath = 'backend/src/test/java/com/playdata/calen/drive/web/DriveAdminSecurityIntegrationTest.java'

$findings = [System.Collections.Generic.List[string]]::new()

foreach ($path in @($contractPath, $securityChecklistPath, $ciPath, $loginAuditServicePath, $adminControllerPath, $driveAdminControllerPath, $loginAuditTestPath, $driveAdminSecurityTestPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Missing admin audit contract input: $path") | Out-Null
    }
}

if ($findings.Count -eq 0) {
    $contract = Get-Content -LiteralPath $contractPath -Raw
    $securityChecklist = Get-Content -LiteralPath $securityChecklistPath -Raw
    $ci = Get-Content -LiteralPath $ciPath -Raw
    $loginAuditService = Get-Content -LiteralPath $loginAuditServicePath -Raw
    $adminController = Get-Content -LiteralPath $adminControllerPath -Raw
    $driveAdminController = Get-Content -LiteralPath $driveAdminControllerPath -Raw
    $loginAuditTest = Get-Content -LiteralPath $loginAuditTestPath -Raw
    $driveAdminSecurityTest = Get-Content -LiteralPath $driveAdminSecurityTestPath -Raw

    $requiredSections = @(
        '# Admin Audit Log Contract',
        '## Scope',
        '## Required audited actions',
        '## Safe detail policy',
        '## Current evidence',
        '## Test and CI gate',
        '## Release gate'
    )
    foreach ($section in $requiredSections) {
        if (-not $contract.Contains($section)) {
            $findings.Add("Admin audit contract missing section: $section") | Out-Null
        }
    }

    $requiredActionCodes = @(
        'DATA_BACKUP_CREATE',
        'MINIO_BACKUP_CREATE',
        'DATA_BACKUP_DOWNLOAD',
        'DATA_RESTORE',
        'DATA_RESTORE_UPLOAD',
        'USER_ACTIVE_UPDATE',
        'BLOCKED_IP_CLEAR',
        'DRIVE_USER_STATUS_UPDATE',
        'DRIVE_STORAGE_CAPACITY_UPDATE'
    )
    foreach ($actionCode in $requiredActionCodes) {
        if (-not $contract.Contains($actionCode)) {
            $findings.Add("Admin audit contract missing required action code: $actionCode") | Out-Null
        }
        $source = if ($actionCode.StartsWith('DRIVE_')) { $driveAdminController } else { $adminController }
        if (-not $source.Contains($actionCode)) {
            $findings.Add("Admin controller source missing required audit action code: $actionCode") | Out-Null
        }
    }

    foreach ($snippet in @('recordAdminAction', 'LoginAuditStatus.ADMIN_ACTION', 'limit(detail)', 'loginAuditLogRepository.save(log)')) {
        if (-not $loginAuditService.Contains($snippet)) {
            $findings.Add("LoginAuditLogService missing audit persistence snippet: $snippet") | Out-Null
        }
    }
    foreach ($snippet in @('LoginAuditLogService', 'recordAdminAction(', 'safeDetail(')) {
        if (-not $adminController.Contains($snippet)) {
            $findings.Add("AdminController missing audit wiring snippet: $snippet") | Out-Null
        }
    }
    foreach ($snippet in @('LoginAuditLogService', 'recordAdminAction(', 'requireVerifiedAdmin')) {
        if (-not $driveAdminController.Contains($snippet)) {
            $findings.Add("DriveAdminController missing audit/verification wiring snippet: $snippet") | Out-Null
        }
    }

    $requiredSafeDetailPhrases = @(
        'password',
        'secondary PIN',
        'API key',
        'secret',
        'token',
        'signed URL',
        'presigned URL',
        'rclone.conf',
        'raw backup contents',
        'raw OCR image data',
        'raw AI prompt',
        'provider response body',
        'full filesystem path'
    )
    foreach ($phrase in $requiredSafeDetailPhrases) {
        if (-not $contract.Contains($phrase)) {
            $findings.Add("Admin audit contract missing safe-detail forbidden phrase: $phrase") | Out-Null
        }
    }

    foreach ($snippet in @('recordAdminActionStoresActorDetailAndAdminActionStatus', 'LoginAuditStatus.ADMIN_ACTION', 'DATA_RESTORE:calen-2026.sql.gz')) {
        if (-not $loginAuditTest.Contains($snippet)) {
            $findings.Add("LoginAuditLogServiceTest missing audit evidence snippet: $snippet") | Out-Null
        }
    }
    foreach ($snippet in @('DRIVE_STORAGE_CAPACITY_UPDATE', 'doesNotContain("password", "token", "key")', 'LoginAuditStatus.ADMIN_ACTION')) {
        if (-not $driveAdminSecurityTest.Contains($snippet)) {
            $findings.Add("DriveAdminSecurityIntegrationTest missing safe audit evidence snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('AUDIT-01', 'docs/admin_audit_log_contract.md', 'scripts/verify-admin-audit-contract.ps1')) {
        if (-not $securityChecklist.Contains($snippet)) {
            $findings.Add("Security baseline missing admin audit contract snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('admin-audit-contract:', './scripts/verify-admin-audit-contract.ps1', '[admin-audit-contract]="${{ needs[''admin-audit-contract''].result }}"')) {
        if (-not $ci.Contains($snippet)) {
            $findings.Add("CI workflow missing admin audit contract snippet: $snippet") | Out-Null
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'Admin audit contract verification failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Admin audit contract verification passed.'