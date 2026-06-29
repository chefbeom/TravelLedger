Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$checklistPath = 'docs/e2e_smoke_checklist.md'
$ciPath = '.github/workflows/ci.yml'
$packagePath = 'frontend/package.json'
$playwrightConfigPath = 'frontend/playwright.config.js'
$playwrightSmokePath = 'frontend/e2e/smoke.spec.js'
$findings = [System.Collections.Generic.List[string]]::new()

foreach ($path in @($checklistPath, $ciPath, $packagePath, $playwrightConfigPath, $playwrightSmokePath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Missing E2E smoke artifact: $path") | Out-Null
    }
}

if ($findings.Count -eq 0) {
    $checklist = Get-Content -LiteralPath $checklistPath -Raw
    $requiredSections = @(
        '# E2E smoke checklist',
        '## Shared setup',
        '## P0 smoke flows',
        '## Playwright smoke skeleton',
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

    $requiredPlaywrightDocSnippets = @(
        'frontend/playwright.config.js',
        'frontend/e2e/smoke.spec.js',
        'npm run test:e2e:install',
        'npm run test:e2e:smoke',
        'E2E_BASE_URL',
        'E2E_START_LOCAL_SERVER=0',
        'E2E_USER_LOGIN_ID',
        'E2E_SECOND_USER_LOGIN_ID',
        'E2E_ADMIN_LOGIN_ID',
        'E2E_ALLOW_MUTATING_SMOKE=1',
        'E2E_PROVIDER_MODE=stubbed',
        'workspace checkpoints alone are not enough for high-risk changes'
    )
    foreach ($snippet in $requiredPlaywrightDocSnippets) {
        if (-not $checklist.Contains($snippet)) {
            $findings.Add("Checklist missing Playwright skeleton snippet: $snippet") | Out-Null
        }
    }

    $package = Get-Content -LiteralPath $packagePath -Raw | ConvertFrom-Json
    $scriptNames = @($package.scripts.PSObject.Properties.Name)
    foreach ($scriptName in @('test:e2e:install', 'test:e2e:smoke', 'test:e2e:smoke:headed')) {
        if ($scriptNames -notcontains $scriptName) {
            $findings.Add("frontend/package.json missing script: $scriptName") | Out-Null
        }
    }
    if ($scriptNames -contains 'test:e2e:smoke') {
        $smokeScript = $package.scripts.'test:e2e:smoke'
        foreach ($snippet in @('@playwright/test', 'playwright test', 'playwright.config.js')) {
            if (-not $smokeScript.Contains($snippet)) {
                $findings.Add("test:e2e:smoke script missing snippet: $snippet") | Out-Null
            }
        }
    }

    $playwrightConfig = Get-Content -LiteralPath $playwrightConfigPath -Raw
    foreach ($snippet in @('E2E_BASE_URL', 'PLAYWRIGHT_BASE_URL', '1440', '390', 'chromium-desktop', 'chromium-mobile', 'webServer', 'E2E_START_LOCAL_SERVER')) {
        if (-not $playwrightConfig.Contains($snippet)) {
            $findings.Add("Playwright config missing snippet: $snippet") | Out-Null
        }
    }

    $playwrightSmoke = Get-Content -LiteralPath $playwrightSmokePath -Raw
    foreach ($snippet in @(
        "import { expect, test } from '@playwright/test'",
        'P0 scenario inventory matches release checklist',
        'public app shell loads without authenticated fixtures',
        'P0 Login and session smoke',
        'E2E_USER_LOGIN_ID',
        'E2E_SECOND_USER_LOGIN_ID',
        'E2E_ADMIN_LOGIN_ID',
        'E2E_ALLOW_MUTATING_SMOKE',
        'E2E_PROVIDER_MODE',
        '/api/auth/csrf',
        '/api/auth/login',
        '/api/auth/me',
        '/api/auth/logout',
        'fixture gate and workspace checkpoint'
    )) {
        if (-not $playwrightSmoke.Contains($snippet)) {
            $findings.Add("Playwright smoke spec missing snippet: $snippet") | Out-Null
        }
    }
    foreach ($flow in $requiredFlows) {
        if (-not $playwrightSmoke.Contains($flow)) {
            $findings.Add("Playwright smoke spec missing P0 flow: $flow") | Out-Null
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
