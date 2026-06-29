Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$checklistPath = 'docs/e2e_smoke_checklist.md'
$ciPath = '.github/workflows/ci.yml'
$findings = [System.Collections.Generic.List[string]]::new()

if (-not (Test-Path -LiteralPath $checklistPath)) {
    $findings.Add("Missing E2E smoke checklist: $checklistPath") | Out-Null
}
if (-not (Test-Path -LiteralPath $ciPath)) {
    $findings.Add("Missing CI workflow: $ciPath") | Out-Null
}

if ($findings.Count -eq 0) {
    $checklist = Get-Content -LiteralPath $checklistPath -Raw
    $requiredSections = @(
        '# E2E smoke checklist',
        '## Shared setup',
        '## P0 smoke flows',
        '## Automation readiness contract',
        '## Release evidence template',
        '## Automation conversion notes',
        '## Gate policy'
    )
    foreach ($section in $requiredSections) {
        if (-not $checklist.Contains($section)) {
            $findings.Add("Checklist missing section: $section") | Out-Null
        }
    }

    $requiredFlows = @(
        'Login and session',
        'Ledger entry create/edit/delete',
        'Excel import preview and confirm',
        'OCR confirm-save',
        'Travel photo upload',
        'CalenDrive share',
        'Admin backup action',
        'AI analysis advisory',
        'Notification center'
    )
    foreach ($flow in $requiredFlows) {
        if (-not $checklist.Contains($flow)) {
            $findings.Add("Checklist missing P0 flow: $flow") | Out-Null
        }
    }

    $requiredEvidenceFields = @(
        'Commit SHA:',
        'Environment URL:',
        'Backend profile/config summary:',
        'Fixture set:',
        'Provider mode: stubbed | live-readonly | mixed',
        'Browser contexts used:',
        'Automation run URL or artifact path:',
        'Tester:',
        'Date/time:',
        'Desktop browser/version:',
        'Mobile browser/device or emulator:',
        'Release decision:'
    )
    foreach ($field in $requiredEvidenceFields) {
        if (-not $checklist.Contains($field)) {
            $findings.Add("Checklist missing evidence field: $field") | Out-Null
        }
    }

    $requiredReadinessSnippets = @(
        'Stable selectors',
        'data-testid',
        'Disposable fixtures',
        'Provider stubbing',
        'OCR/AI success, timeout, and failure fixtures',
        'Cross-user contexts',
        'two authenticated browser contexts plus one unauthorized or third-user context',
        'Mutation safety',
        'before explicit user confirmation',
        'Admin guardrail',
        'secondary verification denied',
        'Mobile/accessibility pass',
        'Artifact hygiene',
        'must not log API keys, public-link tokens, presigned URLs, raw OCR images, raw AI prompts, or secondary PIN values'
    )
    foreach ($snippet in $requiredReadinessSnippets) {
        if (-not $checklist.Contains($snippet)) {
            $findings.Add("Checklist missing automation readiness snippet: $snippet") | Out-Null
        }
    }

    $ci = Get-Content -LiteralPath $ciPath -Raw
    foreach ($snippet in @('frontend-e2e-smoke-checklist:', './scripts/verify-e2e-smoke-checklist.ps1', "[frontend-e2e-smoke-checklist]=`"")) {
        if (-not $ci.Contains($snippet)) {
            $findings.Add("CI workflow missing E2E checklist snippet: $snippet") | Out-Null
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'E2E smoke checklist verification failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'E2E smoke checklist verification passed.'