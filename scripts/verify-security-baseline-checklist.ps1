Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$baselinePath = 'docs/security_baseline_checklist.md'
$ciPath = '.github/workflows/ci.yml'
$supportInquiryTestPath = 'backend/src/test/java/com/playdata/calen/account/SupportInquiryIntegrationTest.java'
$findings = [System.Collections.Generic.List[string]]::new()

foreach ($path in @($baselinePath, $ciPath, $supportInquiryTestPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Missing security baseline input: $path") | Out-Null
    }
}

function Assert-ContainsAll([string]$Label, [string]$Content, [string[]]$Needles) {
    foreach ($needle in $Needles) {
        if (-not $Content.Contains($needle)) {
            $script:findings.Add("$Label missing required snippet: $needle") | Out-Null
        }
    }
}

if ($findings.Count -eq 0) {
    $baseline = Get-Content -LiteralPath $baselinePath -Raw
    $ci = Get-Content -LiteralPath $ciPath -Raw
    $supportInquiryTest = Get-Content -LiteralPath $supportInquiryTestPath -Raw

    Assert-ContainsAll 'Security baseline sections' $baseline @(
        '# Security Baseline Checklist',
        '## Scope',
        '## OWASP ASVS Traceability Matrix',
        '## Baseline Rules',
        '## Public Route Allowlist Review',
        '## Immediate Test Backlog',
        '## Release Gate',
        '## CI Security Gate'
    )

    Assert-ContainsAll 'ASVS traceability matrix' $baseline @(
        'ASVS V1 Architecture and threat modeling',
        'ASVS V2 Authentication',
        'ASVS V3 Session Management',
        'ASVS V4 Access Control',
        'ASVS V5 Validation/Sanitization',
        'ASVS V8 Data Protection',
        'ASVS V10 File and Resource Safety',
        'ASVS V14 Configuration',
        'ASVS V15 API and business logic'
    )

    Assert-ContainsAll 'Security-sensitive surface coverage' $baseline @(
        'Authentication/session',
        'Remember-me',
        'CSRF',
        'Admin APIs',
        'Shared links',
        'Presigned URL',
        'OCR/AI API keys',
        'Runtime configuration',
        'Observability/audit'
    )

    Assert-ContainsAll 'Baseline rule anchors' $baseline @(
        'AUTH-01', 'AUTH-02', 'AUTH-03', 'ACCESS-01', 'ACCESS-03', 'SHARE-01',
        'UPLOAD-01', 'PRESIGN-01', 'AI-05', 'AUDIT-01', 'OBS-01', 'SECRET-01', 'CI-01'
    )
    Assert-ContainsAll 'Admin support inquiry baseline anchors' $baseline @(
        'support inquiry admin inbox/reply/archive/delete routes',
        'SupportInquiryIntegrationTest.adminSupportInquiryApisRequireVerifiedAdminAndCsrf'
    )

    Assert-ContainsAll 'Admin support inquiry authorization test' $supportInquiryTest @(
        'adminSupportInquiryApisRequireVerifiedAdminAndCsrf',
        'get("/api/admin/support-inquiries")',
        'put("/api/admin/support-inquiries/{inquiryId}/reply"',
        'patch("/api/admin/support-inquiries/{inquiryId}/archive"',
        'delete("/api/admin/support-inquiries/{inquiryId}"',
        'status().isUnauthorized()'
    )

    Assert-ContainsAll 'Contract/verifier anchors' $baseline @(
        'scripts/verify-env-sync.ps1',
        'scripts/verify-db-migrations.ps1',
        'scripts/verify-prometheus-alerts.ps1',
        'scripts/verify-file-upload-security-contract.ps1',
        'scripts/verify-public-share-authorization-contract.ps1',
        'scripts/verify-ai-provider-safety-contract.ps1',
        'scripts/verify-admin-audit-contract.ps1',
        'scripts/scan-secrets.ps1',
        'docs/secret_scanning_contract.md',
        'scripts/verify-secret-scan-contract.ps1',
        'docs/ci_workflow_contract.md',
        'scripts/verify-ci-workflow-contract.ps1'
    )

    Assert-ContainsAll 'Public route allowlist' $baseline @(
        '/api/auth/csrf',
        '/api/auth/login',
        '/api/auth/logout',
        '/api/file/public-download/*',
        '/actuator/health',
        '/actuator/prometheus'
    )

    if ($baseline.Contains('|`n|')) {
        $findings.Add('Security baseline contains a literal `n table separator; use a real newline.') | Out-Null
    }

    Assert-ContainsAll 'CI security baseline job' $ci @(
        'security-baseline-checklist:',
        'Verify ASVS security baseline checklist',
        './scripts/verify-security-baseline-checklist.ps1',
        '[security-baseline-checklist]="${{ needs[''security-baseline-checklist''].result }}"',
        'ci-workflow-contract:',
        './scripts/verify-ci-workflow-contract.ps1',
        '[ci-workflow-contract]="${{ needs[''ci-workflow-contract''].result }}"'
    )
}

if ($findings.Count -gt 0) {
    Write-Host 'Security baseline checklist verification failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Security baseline checklist verification passed.'
