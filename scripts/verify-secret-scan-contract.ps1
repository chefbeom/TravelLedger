Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$findings = [System.Collections.Generic.List[string]]::new()

function Read-RequiredFile([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        $findings.Add("Missing required file: $Path") | Out-Null
        return ''
    }
    return Get-Content -LiteralPath $Path -Raw
}

function Assert-Contains([string] $Label, [string] $Content, [string] $Needle) {
    if (-not $Content.Contains($Needle)) {
        $findings.Add("${Label} missing required snippet: $Needle") | Out-Null
    }
}

function Assert-ContainsAll([string] $Label, [string] $Content, [string[]] $Needles) {
    foreach ($needle in $Needles) {
        Assert-Contains $Label $Content $needle
    }
}

$scan = Read-RequiredFile 'scripts/scan-secrets.ps1'
$contract = Read-RequiredFile 'docs/secret_scanning_contract.md'
$baseline = Read-RequiredFile 'docs/security_baseline_checklist.md'
$roadmap = Read-RequiredFile 'docs/project_improvement_roadmap.md'
$workflow = Read-RequiredFile '.github/workflows/ci.yml'

Assert-ContainsAll 'scripts/scan-secrets.ps1' $scan @(
    'git ls-files',
    'AWS access key',
    'Private key block',
    'GitHub classic token',
    'GitHub fine-grained token',
    'GitLab personal access token',
    'npm access token',
    'OpenAI-style API key',
    'Google API key',
    'Stripe live secret key',
    'SendGrid API key',
    'Slack token',
    'JWT token',
    'Sensitive environment assignment',
    'ACCESS_KEY',
    'CREDENTIAL',
    'PASSPHRASE',
    'placeholder',
    'sample',
    'fixture',
    'Test-ExcludedPath'
)

Assert-ContainsAll 'docs/secret_scanning_contract.md' $contract @(
    '# Secret Scanning Contract',
    '## Scope',
    '## Required workflow',
    '## CI contract',
    '## Release evidence',
    'scripts/scan-secrets.ps1',
    'scripts/verify-secret-scan-contract.ps1',
    'secret-scan',
    'secret-scan-contract',
    'release-gate',
    'rotate it before merging',
    'obvious placeholder'
)

Assert-ContainsAll 'docs/security_baseline_checklist.md' $baseline @(
    'SECRET-01',
    'docs/secret_scanning_contract.md',
    'scripts/scan-secrets.ps1',
    'scripts/verify-secret-scan-contract.ps1',
    'secret-scan',
    'secret-scan-contract',
    'high-risk committed secret patterns'
)

Assert-ContainsAll 'docs/project_improvement_roadmap.md' $roadmap @(
    'docs/secret_scanning_contract.md',
    'scripts/verify-secret-scan-contract.ps1',
    'secret-scan-contract',
    'high-risk secret gates'
)

Assert-ContainsAll '.github/workflows/ci.yml' $workflow @(
    'secret-scan:',
    './scripts/scan-secrets.ps1',
    'secret-scan-contract:',
    './scripts/verify-secret-scan-contract.ps1',
    '- secret-scan-contract',
    '[secret-scan-contract]="${{ needs[''secret-scan-contract''].result }}"'
)

if ($findings.Count -gt 0) {
    Write-Host 'Secret scan contract check failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Secret scan contract check passed.'