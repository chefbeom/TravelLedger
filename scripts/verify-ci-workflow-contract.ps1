Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$findings = [System.Collections.Generic.List[string]]::new()
$doubleQuote = [char]34
$singleQuote = [char]39

function Read-RequiredFile([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        $findings.Add('Missing required file: ' + $Path) | Out-Null
        return ''
    }
    return Get-Content -LiteralPath $Path -Raw
}

function Assert-Contains([string] $Label, [string] $Content, [string] $Needle) {
    if (-not $Content.Contains($Needle)) {
        $findings.Add($Label + ' missing required snippet: ' + $Needle) | Out-Null
    }
}

$ci = Read-RequiredFile '.github/workflows/ci.yml'
$contract = Read-RequiredFile 'docs/ci_workflow_contract.md'
$baseline = Read-RequiredFile 'docs/security_baseline_checklist.md'
$roadmap = Read-RequiredFile 'docs/project_improvement_roadmap.md'
$securityVerifier = Read-RequiredFile 'scripts/verify-security-baseline-checklist.ps1'

$requiredJobs = @(
    'secret-scan',
    'secret-scan-contract',
    'ci-workflow-contract',
    'config-sync',
    'migration-discipline',
    'observability-alerts',
    'backup-rehearsal-runbook',
    'security-baseline-checklist',
    'admin-audit-contract',
    'privacy-control-contract',
    'public-share-authorization-contract',
    'media-processing-contract',
    'file-upload-security-contract',
    'ledger-ai-coach-contract',
    'ledger-classification-contract',
    'ledger-anomaly-contract',
    'travel-story-export-contract',
    'household-budget-goals-contract',
    'notification-center-contract',
    'drive-file-versioning-contract',
    'data-portability-contract',
    'pwa-mobile-baseline',
    'accessibility-mobile-checklist',
    'ai-provider-safety-contract',
    'service-decomposition-plan',
    'backend-test',
    'backend-security-tests',
    'frontend-build',
    'frontend-e2e-smoke-checklist'
)

foreach ($job in $requiredJobs) {
    $jobKey = '  ' + $job + ':'
    $needsNeedle = '      - ' + $job
    $resultNeedle = '[' + $job + ']=' + $doubleQuote + '${{ needs[' + $singleQuote + $job + $singleQuote + '].result }}' + $doubleQuote
    $contractNeedle = '`' + $job + '`'

    Assert-Contains '.github/workflows/ci.yml job key' $ci $jobKey
    Assert-Contains '.github/workflows/ci.yml release-gate needs' $ci $needsNeedle
    Assert-Contains '.github/workflows/ci.yml release-gate results' $ci $resultNeedle
    Assert-Contains 'docs/ci_workflow_contract.md' $contract $contractNeedle
}

Assert-Contains '.github/workflows/ci.yml ci workflow verifier command' $ci './scripts/verify-ci-workflow-contract.ps1'
Assert-Contains 'docs/security_baseline_checklist.md' $baseline 'CI-01'
Assert-Contains 'docs/security_baseline_checklist.md' $baseline 'docs/ci_workflow_contract.md'
Assert-Contains 'docs/security_baseline_checklist.md' $baseline 'scripts/verify-ci-workflow-contract.ps1'
Assert-Contains 'docs/project_improvement_roadmap.md' $roadmap 'docs/ci_workflow_contract.md'
Assert-Contains 'docs/project_improvement_roadmap.md' $roadmap 'scripts/verify-ci-workflow-contract.ps1'
Assert-Contains 'scripts/verify-security-baseline-checklist.ps1' $securityVerifier 'CI-01'
Assert-Contains 'scripts/verify-security-baseline-checklist.ps1' $securityVerifier 'scripts/verify-ci-workflow-contract.ps1'

$gluedKeyPattern = [regex]'(?m)^\s*(run|uses|name|shell|with|if|timeout-minutes|working-directory):.*\s{2,}[A-Za-z0-9_-]+:\s*$'
foreach ($match in $gluedKeyPattern.Matches($ci)) {
    $findings.Add('Possible glued YAML key in .github/workflows/ci.yml: ' + $match.Value.Trim()) | Out-Null
}

if ($findings.Count -gt 0) {
    Write-Host 'CI workflow contract check failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host (' - ' + $_) }
    exit 1
}

Write-Host 'CI workflow contract check passed.'