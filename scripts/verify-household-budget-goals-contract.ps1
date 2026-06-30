Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$contractPath = 'docs/household_budget_goals.md'
$securityChecklistPath = 'docs/security_baseline_checklist.md'
$roadmapPath = 'docs/project_improvement_roadmap.md'
$ciPath = '.github/workflows/ci.yml'
$aggregateServicePath = 'backend/src/main/java/com/playdata/calen/account/service/HouseholdAggregatePreferenceService.java'
$aggregateTestPath = 'backend/src/test/java/com/playdata/calen/account/HouseholdAggregatePreferenceServiceTest.java'
$goalDomainPath = 'backend/src/main/java/com/playdata/calen/account/domain/HouseholdGoal.java'
$goalRepositoryPath = 'backend/src/main/java/com/playdata/calen/account/repository/HouseholdGoalRepository.java'
$goalServicePath = 'backend/src/main/java/com/playdata/calen/account/service/HouseholdGoalService.java'
$goalSchemaUpdaterPath = 'backend/src/main/java/com/playdata/calen/account/config/HouseholdGoalSchemaUpdater.java'
$goalControllerPath = 'backend/src/main/java/com/playdata/calen/account/web/AccountPreferenceController.java'
$goalServiceTestPath = 'backend/src/test/java/com/playdata/calen/account/HouseholdGoalServiceTest.java'
$travelBudgetRepositoryPath = 'backend/src/main/java/com/playdata/calen/travel/repository/TravelBudgetItemRepository.java'
$travelBudgetDomainPath = 'backend/src/main/java/com/playdata/calen/travel/domain/TravelBudgetItem.java'
$householdWorkspacePath = 'frontend/src/components/HouseholdWorkspace.vue'

$findings = [System.Collections.Generic.List[string]]::new()

foreach ($path in @($contractPath, $securityChecklistPath, $roadmapPath, $ciPath, $aggregateServicePath, $aggregateTestPath, $goalDomainPath, $goalRepositoryPath, $goalServicePath, $goalSchemaUpdaterPath, $goalControllerPath, $goalServiceTestPath, $travelBudgetRepositoryPath, $travelBudgetDomainPath, $householdWorkspacePath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Missing household budget/goals contract input: $path") | Out-Null
    }
}

if ($findings.Count -eq 0) {
    $contract = Get-Content -LiteralPath $contractPath -Raw
    $securityChecklist = Get-Content -LiteralPath $securityChecklistPath -Raw
    $roadmap = Get-Content -LiteralPath $roadmapPath -Raw
    $ci = Get-Content -LiteralPath $ciPath -Raw
    $aggregateService = Get-Content -LiteralPath $aggregateServicePath -Raw
    $aggregateTest = Get-Content -LiteralPath $aggregateTestPath -Raw
    $goalDomain = Get-Content -LiteralPath $goalDomainPath -Raw
    $goalRepository = Get-Content -LiteralPath $goalRepositoryPath -Raw
    $goalService = Get-Content -LiteralPath $goalServicePath -Raw
    $goalSchemaUpdater = Get-Content -LiteralPath $goalSchemaUpdaterPath -Raw
    $goalController = Get-Content -LiteralPath $goalControllerPath -Raw
    $goalServiceTest = Get-Content -LiteralPath $goalServiceTestPath -Raw
    $travelBudgetRepository = Get-Content -LiteralPath $travelBudgetRepositoryPath -Raw
    $travelBudgetDomain = Get-Content -LiteralPath $travelBudgetDomainPath -Raw
    $householdWorkspace = Get-Content -LiteralPath $householdWorkspacePath -Raw

    foreach ($section in @('# Household Budget and Shared Goals Contract', '## Current baseline', '## Product boundary', '## Target data flow', '## Non-negotiable safety rules', '## Current implementation anchors', '## Release gate', '## CI contract')) {
        if (-not $contract.Contains($section)) {
            $findings.Add("Household budget/goals contract missing section: $section") | Out-Null
        }
    }

    foreach ($phrase in @('owner-scoped or membership-scoped', 'explicit membership/grant rows', 'explicit CSRF-protected mutation', 'non-visible member data', 'Notification producers must use bounded metadata', 'optimistic locking', 'Goal progress does not include another user', 'Owner-scoped personal household goals', 'GOAL_PROGRESS')) {
        if (-not $contract.Contains($phrase)) {
            $findings.Add("Household budget/goals contract missing required phrase: $phrase") | Out-Null
        }
    }

    foreach ($snippet in @('MAX_WIDGETS = 4', 'ALLOWED_KINDS', 'ALLOWED_PERIODS', 'ALLOWED_AMOUNT_TYPES', 'getRequiredActiveUser(userId)', 'user.setHouseholdAggregateSettingsJson', 'paymentMethodRepository.findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(userId)', 'buildDefaultWidgets')) {
        if (-not $aggregateService.Contains($snippet)) {
            $findings.Add("HouseholdAggregatePreferenceService missing scoped widget snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('getPreferences_handlesLegacyOrSparseStoredWidgets', 'findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(1L)', 'response.widgets()).hasSize(4)', 'paymentMethodId()).isEqualTo(7L)')) {
        if (-not $aggregateTest.Contains($snippet)) {
            $findings.Add("HouseholdAggregatePreferenceServiceTest missing evidence snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('findAllByPlanIdAndPlanOwnerIdOrderByDisplayOrderAscIdAsc', 'findAllByPlanOwnerId', 'findByIdAndPlanOwnerId')) {
        if (-not $travelBudgetRepository.Contains($snippet)) {
            $findings.Add("TravelBudgetItemRepository missing owner-scoped budget query: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('TravelPlan plan', 'private String category', 'private BigDecimal amount', 'private String currencyCode', 'private BigDecimal amountKrw', 'private Integer displayOrder')) {
        if (-not $travelBudgetDomain.Contains($snippet)) {
            $findings.Add("TravelBudgetItem missing budget field snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('fetchHouseholdAggregatePreferences', 'saveHouseholdAggregatePreferences', 'aggregateWidgetConfigs', 'HouseholdTravelLedgerWorkspace', 'fetchTravelPlans', 'selectedHouseholdTravelPlanId')) {
        if (-not $householdWorkspace.Contains($snippet)) {
            $findings.Add("HouseholdWorkspace missing household budget UI anchor: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('HOUSEHOLD-01', 'Household budget/shared goal', 'docs/household_budget_goals.md', 'household-budget-goals-contract', 'scripts/verify-household-budget-goals-contract.ps1')) {
        if (-not $securityChecklist.Contains($snippet)) {
            $findings.Add("Security baseline missing household budget/goals snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('Family budget/shared goals', 'docs/household_budget_goals.md', 'household-budget-goals-contract', 'scripts/verify-household-budget-goals-contract.ps1')) {
        if (-not $roadmap.Contains($snippet)) {
            $findings.Add("Project roadmap missing household budget/goals snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('household-budget-goals-contract:', './scripts/verify-household-budget-goals-contract.ps1', '[household-budget-goals-contract]="${{ needs[''household-budget-goals-contract''].result }}"')) {
        if (-not $ci.Contains($snippet)) {
            $findings.Add("CI workflow missing household budget/goals contract snippet: $snippet") | Out-Null
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'Household budget/goals contract verification failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Household budget/goals contract verification passed.'