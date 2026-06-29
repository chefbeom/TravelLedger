Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$contractPath = 'docs/ledger_ai_coach_contract.md'
$safetyPath = 'docs/ledger_ai_safety_hardening.md'
$securityChecklistPath = 'docs/security_baseline_checklist.md'
$ciPath = '.github/workflows/ci.yml'
$reportDtoPath = 'backend/src/main/java/com/playdata/calen/ledger/dto/LedgerAiAnalysisReportResponse.java'
$responseDtoPath = 'backend/src/main/java/com/playdata/calen/ledger/dto/LedgerAiAnalysisResponse.java'
$servicePath = 'backend/src/main/java/com/playdata/calen/ledger/ai/LedgerAiAnalysisService.java'
$validatorPath = 'backend/src/main/java/com/playdata/calen/ledger/ai/LedgerAiRemoteResponseValidator.java'
$serviceTestPath = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAiAnalysisServiceTest.java'
$validatorTestPath = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAiRemoteResponseValidatorTest.java'

$findings = [System.Collections.Generic.List[string]]::new()

foreach ($path in @($contractPath, $safetyPath, $securityChecklistPath, $ciPath, $reportDtoPath, $responseDtoPath, $servicePath, $validatorPath, $serviceTestPath, $validatorTestPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Missing ledger AI coach contract input: $path") | Out-Null
    }
}

if ($findings.Count -eq 0) {
    $contract = Get-Content -LiteralPath $contractPath -Raw
    $safety = Get-Content -LiteralPath $safetyPath -Raw
    $securityChecklist = Get-Content -LiteralPath $securityChecklistPath -Raw
    $ci = Get-Content -LiteralPath $ciPath -Raw
    $reportDto = Get-Content -LiteralPath $reportDtoPath -Raw
    $responseDto = Get-Content -LiteralPath $responseDtoPath -Raw
    $service = Get-Content -LiteralPath $servicePath -Raw
    $validator = Get-Content -LiteralPath $validatorPath -Raw
    $serviceTest = Get-Content -LiteralPath $serviceTestPath -Raw
    $validatorTest = Get-Content -LiteralPath $validatorTestPath -Raw

    foreach ($section in @('# Ledger AI Coach Contract', '## Coach outcomes', '## Non-negotiable safety rules', '## Current implementation anchors', '## Provider prompt contract', '## Release gate', '## CI contract')) {
        if (-not $contract.Contains($section)) {
            $findings.Add("Ledger AI coach contract missing section: $section") | Out-Null
        }
    }

    foreach ($phrase in @('Risk spending', 'Recurring/subscription spend', 'Budget overrun forecast', 'Cashflow coaching', 'Category/payment coaching', 'advisory-only analysis', 'separate explicit user confirmation/save action', 'created, updated, deleted, saved, categorized, reclassified', 'uncertainty language')) {
        if (-not $contract.Contains($phrase)) {
            $findings.Add("Ledger AI coach contract missing required phrase: $phrase") | Out-Null
        }
    }

    foreach ($snippet in @('## AI Coach Contract', 'docs/ledger_ai_coach_contract.md', 'risk spending', 'recurring/subscription spend', 'budget overrun forecast', 'next-period cashflow coaching', 'verify-ledger-ai-coach-contract.ps1')) {
        if (-not $safety.Contains($snippet)) {
            $findings.Add("Ledger AI safety plan missing coach snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('abnormalSpending', 'subscriptions', 'fixedExpenses', 'improvementActions', 'comparisonFocus')) {
        if (-not $reportDto.Contains($snippet)) {
            $findings.Add("LedgerAiAnalysisReportResponse missing coach field: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('nextPeriodForecast', 'habitAssessment', 'unusualSpendingInsights', 'fixedCostInsights', 'recommendations')) {
        if (-not $responseDto.Contains($snippet)) {
            $findings.Add("LedgerAiAnalysisResponse missing coach field: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('report.abnormalSpending()', 'report.subscriptions()', 'report.fixedExpenses()', 'report.improvementActions()', 'remote.nextPeriodForecast()', 'remote.habitAssessment()', 'remote.unusualSpendingInsights()', 'remote.fixedCostInsights()', 'outputContract()', 'DUPLICATE_SUPPRESSION_WINDOW')) {
        if (-not $service.Contains($snippet)) {
            $findings.Add("LedgerAiAnalysisService missing coach merge/safety snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('secret', 'prompt', 'created', 'updated', 'deleted', 'categorized', 'reclassified')) {
        if (-not $validator.Contains($snippet) -and -not $validatorTest.Contains($snippet)) {
            $findings.Add("AI unsafe-output validator evidence missing snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('analyzeKeepsPromptInjectionLikeLedgerTextAsData', 'analyzeStoresFailedHistoryWithoutLeakingProviderSecrets', 'reusesRecentCompletedAnalysisForDuplicateRequest')) {
        if (-not $serviceTest.Contains($snippet)) {
            $findings.Add("LedgerAiAnalysisServiceTest missing coach safety evidence: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('rejectsSecretLikeProviderOutput', 'rejectsPromptInjectionEchoFromProviderOutput')) {
        if (-not $validatorTest.Contains($snippet)) {
            $findings.Add("LedgerAiRemoteResponseValidatorTest missing unsafe-output evidence: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('ledger-ai-coach-contract', 'docs/ledger_ai_coach_contract.md', 'scripts/verify-ledger-ai-coach-contract.ps1')) {
        if (-not $securityChecklist.Contains($snippet)) {
            $findings.Add("Security baseline missing ledger AI coach contract snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('ledger-ai-coach-contract:', './scripts/verify-ledger-ai-coach-contract.ps1', '[ledger-ai-coach-contract]="${{ needs[''ledger-ai-coach-contract''].result }}"')) {
        if (-not $ci.Contains($snippet)) {
            $findings.Add("CI workflow missing ledger AI coach contract snippet: $snippet") | Out-Null
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'Ledger AI coach contract verification failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Ledger AI coach contract verification passed.'