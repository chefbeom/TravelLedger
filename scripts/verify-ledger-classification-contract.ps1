Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$contractPath = 'docs/ledger_classification_rules.md'
$securityChecklistPath = 'docs/security_baseline_checklist.md'
$ciPath = '.github/workflows/ci.yml'
$controllerPath = 'backend/src/main/java/com/playdata/calen/ledger/web/LedgerClassificationRuleController.java'
$servicePath = 'backend/src/main/java/com/playdata/calen/ledger/service/LedgerClassificationRuleService.java'
$domainPath = 'backend/src/main/java/com/playdata/calen/ledger/domain/LedgerClassificationRule.java'
$repositoryPath = 'backend/src/main/java/com/playdata/calen/ledger/repository/LedgerClassificationRuleRepository.java'
$serviceTestPath = 'backend/src/test/java/com/playdata/calen/ledger/service/LedgerClassificationRuleServiceTest.java'

$findings = [System.Collections.Generic.List[string]]::new()

foreach ($path in @($contractPath, $securityChecklistPath, $ciPath, $controllerPath, $servicePath, $domainPath, $repositoryPath, $serviceTestPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Missing ledger classification contract input: $path") | Out-Null
    }
}

if ($findings.Count -eq 0) {
    $contract = Get-Content -LiteralPath $contractPath -Raw
    $securityChecklist = Get-Content -LiteralPath $securityChecklistPath -Raw
    $ci = Get-Content -LiteralPath $ciPath -Raw
    $controller = Get-Content -LiteralPath $controllerPath -Raw
    $service = Get-Content -LiteralPath $servicePath -Raw
    $domain = Get-Content -LiteralPath $domainPath -Raw
    $repository = Get-Content -LiteralPath $repositoryPath -Raw
    $serviceTest = Get-Content -LiteralPath $serviceTestPath -Raw

    foreach ($section in @('# Ledger Classification Rules', '## Decision flow', '## Matching Rule', '## Cross-feature contract', '## Non-negotiable safety rules', '## Current implementation anchors', '## Release gate', '## CI contract')) {
        if (-not $contract.Contains($section)) {
            $findings.Add("Ledger classification contract missing section: $section") | Out-Null
        }
    }

    foreach ($phrase in @('draft suggestion', 'explicit user confirmation', 'must not create, update, delete, or reclassify', 'OCR preview', 'Excel import preview', 'AI-recommended rule approval', 'AI-recommended rule approval API', 'owner-scoped', 'active-only preview', 'Preview does not mutate ledger data')) {
        if (-not $contract.Contains($phrase)) {
            $findings.Add("Ledger classification contract missing required phrase: $phrase") | Out-Null
        }
    }

    foreach ($snippet in @('@RequestMapping("/api/ledger/classification-rules")', '@PostMapping("/preview")', '@PostMapping("/recommendations/approve")', '@AuthenticationPrincipal AppUserPrincipal currentUser', 'ledgerClassificationRuleService.preview(currentUser.userId(), request)', 'ledgerClassificationRuleService.approveRecommendedRule(currentUser.userId(), request)')) {
        if (-not $controller.Contains($snippet)) {
            $findings.Add("LedgerClassificationRuleController missing endpoint/auth snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('approveRecommendedRule', 'approvedRecommendationRequest', 'findAllByOwnerIdAndActiveTrueOrderByPriorityAscIdAsc(userId)', 'findByIdAndOwnerId(ruleId, userId)', 'rule.setActive(false)', 'normalizeText', 'haystack.contains(rule.getNormalizedKeyword())', 'categoryDetailRepository.findByIdAndGroupOwnerId', 'paymentMethodRepository.findByIdAndOwnerId', 'Category detail must belong to the selected category group.', 'Rule priority must be between 1 and 1000.')) {
        if (-not $service.Contains($snippet)) {
            $findings.Add("LedgerClassificationRuleService missing safety/matching snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('idx_ledger_classification_rules_owner_active_priority', 'idx_ledger_classification_rules_owner_keyword', 'owner_id', 'normalized_keyword', 'private int priority', 'private boolean active')) {
        if (-not $domain.Contains($snippet)) {
            $findings.Add("LedgerClassificationRule domain missing persistence snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('findAllByOwnerIdOrderByPriorityAscIdAsc', 'findAllByOwnerIdAndActiveTrueOrderByPriorityAscIdAsc', 'findByIdAndOwnerId')) {
        if (-not $repository.Contains($snippet)) {
            $findings.Add("LedgerClassificationRuleRepository missing owner-scoped query: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('previewReturnsFirstActiveOwnerRuleInPriorityOrder', 'previewDoesNotMatchDifferentEntryTypeRule', 'createRuleRejectsCategoryDetailFromDifferentGroup', 'approveRecommendedRuleCreatesActiveOwnerRuleFromDraft', 'findAllByOwnerIdAndActiveTrueOrderByPriorityAscIdAsc(USER_ID)')) {
        if (-not $serviceTest.Contains($snippet)) {
            $findings.Add("LedgerClassificationRuleServiceTest missing evidence snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('AI-04', 'docs/ledger_classification_rules.md', 'ledger-classification-contract', 'scripts/verify-ledger-classification-contract.ps1', 'explicit user approval')) {
        if (-not $securityChecklist.Contains($snippet)) {
            $findings.Add("Security baseline missing ledger classification contract snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('ledger-classification-contract:', './scripts/verify-ledger-classification-contract.ps1', '[ledger-classification-contract]="${{ needs[''ledger-classification-contract''].result }}"')) {
        if (-not $ci.Contains($snippet)) {
            $findings.Add("CI workflow missing ledger classification contract snippet: $snippet") | Out-Null
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'Ledger classification contract verification failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Ledger classification contract verification passed.'