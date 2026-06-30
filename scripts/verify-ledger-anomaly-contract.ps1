Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$contractPath = 'docs/transaction_anomaly_detection.md'
$securityChecklistPath = 'docs/security_baseline_checklist.md'
$roadmapPath = 'docs/project_improvement_roadmap.md'
$ciPath = '.github/workflows/ci.yml'
$controllerPath = 'backend/src/main/java/com/playdata/calen/ledger/web/LedgerTransactionAnomalyController.java'
$servicePath = 'backend/src/main/java/com/playdata/calen/ledger/service/LedgerTransactionAnomalyService.java'
$serviceTestPath = 'backend/src/test/java/com/playdata/calen/ledger/service/LedgerTransactionAnomalyServiceTest.java'

$findings = [System.Collections.Generic.List[string]]::new()

foreach ($path in @($contractPath, $securityChecklistPath, $roadmapPath, $ciPath, $controllerPath, $servicePath, $serviceTestPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Missing ledger anomaly contract input: $path") | Out-Null
    }
}

if ($findings.Count -eq 0) {
    $contract = Get-Content -LiteralPath $contractPath -Raw
    $securityChecklist = Get-Content -LiteralPath $securityChecklistPath -Raw
    $roadmap = Get-Content -LiteralPath $roadmapPath -Raw
    $ci = Get-Content -LiteralPath $ciPath -Raw
    $controller = Get-Content -LiteralPath $controllerPath -Raw
    $service = Get-Content -LiteralPath $servicePath -Raw
    $serviceTest = Get-Content -LiteralPath $serviceTestPath -Raw

    foreach ($section in @('# Transaction Anomaly Detection', '## Decision flow', '## Current Detectors', '## User-facing anomaly cards', '## Safety Rules', '## Current implementation anchors', '## Release gate', '## CI contract')) {
        if (-not $contract.Contains($section)) {
            $findings.Add("Transaction anomaly contract missing section: $section") | Out-Null
        }
    }

    foreach ($phrase in @('read-only', 'candidates, not facts', 'User confirmation is required', 'Date range is capped at 366 days', 'Result limit is capped at 200 groups', 'owner-scoped travel plans', 'Larger than usual spending', 'Possible duplicate payment', 'Repeated same-amount payment', 'Travel spending outside trip dates', 'review candidate', 'Future dismiss workflows', 'Excel/OCR imported-row duplicate preview')) {
        if (-not $contract.Contains($phrase)) {
            $findings.Add("Transaction anomaly contract missing required phrase: $phrase") | Out-Null
        }
    }

    foreach ($snippet in @('@RequestMapping("/api/entries/anomalies")', '@GetMapping', '@AuthenticationPrincipal AppUserPrincipal currentUser', 'findAnomalies(currentUser.userId(), from, to, limit)')) {
        if (-not $controller.Contains($snippet)) {
            $findings.Add("LedgerTransactionAnomalyController missing endpoint/auth snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('@Transactional(readOnly = true)', 'MAX_LIMIT = 200', 'MAX_RANGE_DAYS = 366', 'findAllByOwnerIdAndDeletedAtIsNullOrderByEntryDateAscIdAsc(userId)', 'findAllByOwnerIdAndDeletedAtIsNullAndEntryDateBetweenOrderByEntryDateAscIdAsc(', 'ownerTravelPlansById(userId)', 'DUPLICATE_SAME_DAY_AMOUNT_TITLE', 'REPEATED_SAME_AMOUNT_TITLE', 'TRAVEL_OUT_OF_RANGE_EXPENSE', 'UNUSUALLY_LARGE_EXPENSE', 'EntryType.EXPENSE', 'Anomaly search range cannot exceed 366 days.')) {
        if (-not $service.Contains($snippet)) {
            $findings.Add("LedgerTransactionAnomalyService missing detector/safety snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('findAnomaliesGroupsSameDaySameAmountNormalizedTitleExpensesOnly', 'findAnomaliesFlagsRepeatedSameAmountTitleAcrossMonths', 'findAnomaliesFlagsTravelLinkedExpenseOutsideOwnedPlanDateRange', 'findAnomaliesFlagsUnusuallyLargeExpenseAgainstMedianExpense', 'findAnomaliesCapsReturnedGroupsWithoutChangingTotalGroups', 'findAnomaliesRejectsRangeLongerThan366DaysBeforeReadingEntries', 'never()).findAllByOwnerIdAndDeletedAtIsNullOrderByEntryDateAscIdAsc')) {
        if (-not $serviceTest.Contains($snippet)) {
            $findings.Add("LedgerTransactionAnomalyServiceTest missing evidence snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('LEDGER-01', 'Transaction anomaly detection', 'docs/transaction_anomaly_detection.md', 'ledger-anomaly-contract', 'scripts/verify-ledger-anomaly-contract.ps1')) {
        if (-not $securityChecklist.Contains($snippet)) {
            $findings.Add("Security baseline missing ledger anomaly contract snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('Transaction anomaly detection', 'duplicate, repeated-payment, unusually large, and travel-out-of-range', 'ledger-anomaly-contract', 'scripts/verify-ledger-anomaly-contract.ps1')) {
        if (-not $roadmap.Contains($snippet)) {
            $findings.Add("Project roadmap missing ledger anomaly snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('ledger-anomaly-contract:', './scripts/verify-ledger-anomaly-contract.ps1', '[ledger-anomaly-contract]="${{ needs[''ledger-anomaly-contract''].result }}"')) {
        if (-not $ci.Contains($snippet)) {
            $findings.Add("CI workflow missing ledger anomaly contract snippet: $snippet") | Out-Null
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'Ledger anomaly contract verification failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Ledger anomaly contract verification passed.'