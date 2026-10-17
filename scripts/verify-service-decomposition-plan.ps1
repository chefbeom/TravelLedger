Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$planPath = 'docs/service_decomposition_plan.md'
$statusServicePath = 'backend/src/main/java/com/playdata/calen/ledger/ai/LedgerAiAnalysisStatusService.java'
$statusServiceTestPath = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAiAnalysisStatusServiceTest.java'
$reportMergerPath = 'backend/src/main/java/com/playdata/calen/ledger/ai/LedgerAiAnalysisReportMerger.java'
$reportMergerTestPath = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAiAnalysisReportMergerTest.java'
if (-not (Test-Path -LiteralPath $planPath)) {
    throw "Service decomposition plan not found: $planPath"
}

$content = Get-Content -LiteralPath $planPath -Raw
$findings = [System.Collections.Generic.List[string]]::new()

$trackedServices = @(
    @{
        Name = 'LedgerAiAnalysisService'
        Path = 'backend/src/main/java/com/playdata/calen/ledger/ai/LedgerAiAnalysisService.java'
        MaxLines = 412
        RequiredSections = @('## Ledger AI Extraction Queue', '### Ledger AI Exit Criteria')
    },
    @{
        Name = 'TravelService'
        Path = 'backend/src/main/java/com/playdata/calen/travel/service/TravelService.java'
        MaxLines = 393
        RequiredSections = @('## Responsibility Boundary Contract', '## Decomposition Ratchet Rules', '## Travel Service Extraction Queue', '### Travel Exit Criteria')
    }
)

foreach ($service in $trackedServices) {
    $name = $service.Name
    $path = $service.Path
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Tracked service file is missing: $path") | Out-Null
        continue
    }

    $actualLines = (Get-Content -LiteralPath $path).Count
    $maxLines = [int]$service.MaxLines
    if ($actualLines -gt $maxLines) {
        $findings.Add("Tracked service exceeds decomposition line budget: $name actual=$actualLines max=$maxLines") | Out-Null
    }

    $escapedName = [regex]::Escape($name)
    $pattern = [regex]("\| ``" + $escapedName + "`` \| (?<lines>\d+) lines \|")
    $match = $pattern.Match($content)
    if (-not $match.Success) {
        $findings.Add("Service decomposition plan is missing Current Baseline row for $name.") | Out-Null
    } else {
        $documentedLines = [int]$match.Groups['lines'].Value
        if ($documentedLines -ne $actualLines) {
            $findings.Add("Service decomposition plan line count for $name is stale: documented=$documentedLines actual=$actualLines") | Out-Null
        }
    }

    $budgetPattern = [regex]("\| ``" + $escapedName + "`` \| (?<baseline>\d+) lines \| (?<budget>\d+) lines \|")
    $budgetMatch = $budgetPattern.Match($content)
    if (-not $budgetMatch.Success) {
        $findings.Add("Service decomposition plan is missing CI Line Budget row for $name.") | Out-Null
    } else {
        $documentedBaseline = [int]$budgetMatch.Groups['baseline'].Value
        $documentedBudget = [int]$budgetMatch.Groups['budget'].Value
        if ($documentedBaseline -ne $actualLines) {
            $findings.Add("Service decomposition CI budget baseline for $name is stale: documented=$documentedBaseline actual=$actualLines") | Out-Null
        }
        if ($documentedBudget -ne $maxLines) {
            $findings.Add("Service decomposition CI budget max for $name is stale: documented=$documentedBudget expected=$maxLines") | Out-Null
        }
    }

    foreach ($section in $service.RequiredSections) {
        if (-not $content.Contains($section)) {
            $findings.Add("Service decomposition plan missing section for ${name}: $section") | Out-Null
        }
    }
}

$requiredGuardrails = @(
    'API stability',
    'Security first',
    'Small slices',
    'Pure before side effects',
    'Test before move',
    'Transaction clarity'
)
foreach ($guardrail in $requiredGuardrails) {
    if (-not $content.Contains($guardrail)) {
        $findings.Add("Service decomposition guardrail missing: $guardrail") | Out-Null
    }
}

$requiredReviewItems = @(
    'Identify the exact methods moved',
    'owner scope, token checks, or provider safety',
    'DTO contracts stable',
    'logs API keys, signed URLs, raw prompts, secondary PINs, or raw public tokens',
    'fewer responsibilities after the change'
)
foreach ($item in $requiredReviewItems) {
    if (-not $content.Contains($item)) {
        $findings.Add("Service decomposition review checklist missing item containing: $item") | Out-Null
    }
}

$requiredBoundarySnippets = @(
    'Responsibility Boundary Contract',
    'Decomposition Ratchet Rules',
    'LedgerAiAnalysisStatusService',
    'LedgerAiAnalysisMetrics',
    'LedgerAiAnalysisJsonCodec',
    'LedgerAiAnalysisTextSanitizer',
    'LedgerAiAnalysisPayloadBuilder',
    'LedgerAiAnalysisReportMerger',
    'LedgerLocalReportPolicy',
    'LedgerLocalReportMapper',
    'LedgerAnalysisCalendarPolicy',
    'PrepareLedgerAnalysisDatasetUseCase',
    'LedgerAnalysisDatasetQueryAdapter',
    'RecoverLedgerAiJobsUseCase',
    'LedgerAiRecoverySources',
    'ManageLedgerAnalysisHistoryUseCase',
    'LedgerAnalysisHistoryQueryAdapter',
    'LedgerAnalysisHistoryJsonAdapter',
    'StartLedgerAnalysisUseCase',
    'LedgerAnalysisAdmissions',
    'LedgerAnalysisHistoryDrafts',
    'ExecuteLedgerAnalysisUseCase',
    'LedgerAnalysisFormats',
    'LedgerAnalysisWorkTelemetry',
    'ManageOwnedTravelMediaUseCase',
    'OwnedTravelMediaCache',
    'TravelMediaHttpResponses',
    'ReadOwnedTravelMapUseCase',
    'TransactionalOwnedTravelMap',
    'ReadOwnedTravelPlansUseCase',
    'OwnedTravelPlansCache',
    'ReadTravelReferenceUseCase',
    'TravelReferencePolicy',
    'JpaOwnedTravelMapReadAdapter',
    'ManageTravelSharingUseCase',
    'JpaTravelSharingAdapter',
    'ReflectTravelLedgerUseCase',
    'TravelBridgeEffects',
    'WriteTravelRouteUseCase',
    'ManageTravelRouteFilesUseCase',
    'TravelRateAdapter',
    'buildPayloadMinimizationSummary',
    'findReusableAnalysis',
    'OwnedTravelMediaController',
    'RefreshTravelPhotoClustersUseCase',
    'Line budgets are a ratchet, not a target.'
)
foreach ($snippet in $requiredBoundarySnippets) {
    if (-not $content.Contains($snippet)) {
        $findings.Add("Service decomposition boundary contract missing snippet: $snippet") | Out-Null
    }
}

foreach ($path in @($statusServicePath, $statusServiceTestPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Service decomposition status extraction file is missing: $path") | Out-Null
    }
}


foreach ($path in @($reportMergerPath, $reportMergerTestPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Report merger extraction file is missing: $path") | Out-Null
    }
}

if ((Test-Path -LiteralPath $reportMergerPath) -and (Test-Path -LiteralPath $reportMergerTestPath)) {
    $reportMerger = Get-Content -LiteralPath $reportMergerPath -Raw
    $reportMergerTest = Get-Content -LiteralPath $reportMergerTestPath -Raw
    foreach ($snippet in @('class LedgerAiAnalysisReportMerger', 'merge(LedgerAiAnalysisReportResponse fallback, Result remote)', 'firstNonBlank', 'firstNonEmpty')) {
        if (-not $reportMerger.Contains($snippet)) {
            $findings.Add("LedgerAiAnalysisReportMerger missing report snippet: $snippet") | Out-Null
        }
    }
    foreach ($snippet in @('remoteReportFieldsOverrideFallbackReport', 'remoteSummaryAndListsFillBlankRemoteReportFields', 'provider summary only', 'remote action')) {
        if (-not $reportMergerTest.Contains($snippet)) {
            $findings.Add("LedgerAiAnalysisReportMergerTest missing report evidence snippet: $snippet") | Out-Null
        }
    }
}
if ((Test-Path -LiteralPath $statusServicePath) -and (Test-Path -LiteralPath $statusServiceTestPath)) {
    $statusService = Get-Content -LiteralPath $statusServicePath -Raw
    $statusServiceTest = Get-Content -LiteralPath $statusServiceTestPath -Raw

    foreach ($snippet in @('public LedgerAiAnalysisStatusResponse getStatus()', 'properties.isEnabled()', 'properties.featureConfig(LedgerAiFeature.LEDGER_ANALYSIS)', 'properties.isFeatureConfigured(LedgerAiFeature.LEDGER_ANALYSIS)', 'properties.featureStatusMessage(LedgerAiFeature.LEDGER_ANALYSIS)')) {
        if (-not $statusService.Contains($snippet)) {
            $findings.Add("LedgerAiAnalysisStatusService missing status snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('getStatusReturnsReadinessWithoutProviderSecretsOrUrls', 'doesNotContain("http://172.18.240.1:1234")', 'doesNotContain("lmstudio-secret-token")', 'doesNotContain("n8n-secret-token")')) {
        if (-not $statusServiceTest.Contains($snippet)) {
            $findings.Add("LedgerAiAnalysisStatusServiceTest missing redaction evidence snippet: $snippet") | Out-Null
        }
    }
}
# This bridge is a cross-context write boundary, not a folder-only extraction.
$reflectionFiles = @{
    Core = 'backend/src/main/java/com/playdata/calen/ledger/application/service/ReflectTravelLedgerService.java'
    Transaction = 'backend/src/main/java/com/playdata/calen/ledger/adapter/in/transaction/TransactionalTravelLedgerReflection.java'
    Writer = 'backend/src/main/java/com/playdata/calen/ledger/adapter/out/entry/LinkedLedgerWriter.java'
    Effects = 'backend/src/main/java/com/playdata/calen/ledger/adapter/out/travel/TravelBridgeEffects.java'
    Integration = 'backend/src/test/java/com/playdata/calen/ledger/TravelLedgerReflectionIntegrationTest.java'
    Differential = 'backend/src/test/java/com/playdata/calen/ledger/adapter/support/LedgerTravelBridgeDifferentialTest.java'
    Oracle = 'backend/src/test/java/com/playdata/calen/ledger/fixture/LedgerTravelBridgeLegacyOracle.java'
}
$reflectionSources = @{}
foreach ($entry in $reflectionFiles.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) { $findings.Add("Reflection boundary missing: $($entry.Value)") | Out-Null }
    else { $reflectionSources[$entry.Key] = Get-Content -LiteralPath $entry.Value -Raw }
}
$reflectionEvidence = @{
    Core = @('LedgerTravelPolicy.forward', 'store.linkedLedger', 'LedgerTravelPolicy.reverse', 'store.saveTravel', 'writer.update', 'store.travelResult', 'effects.travelChanged', 'Boolean.FALSE : existing.shared()')
    Transaction = @('@Transactional', 'implements ReflectTravelLedgerUseCase')
    Writer = @('Propagation.MANDATORY', 'LedgerEntryService', 'entries.create', 'entries.update')
    Effects = @('Propagation.MANDATORY', 'summaries.invalidateOwner', 'TravelClusterRefreshRequests.enqueue', 'projection.refreshSynchronously')
    Integration = @('jdbc:h2:mem:travel-ledger-reflection', 'forwardWriterFailureRollsBackNewDefaultReferences', 'invalidResponseColorRollsBack', 'failureAfterInvalidationRollsBackSql', 'outerTransactionMergesDirtyRequests', 'reverseMissingForeignMemoryAndNullKindPointers')
    Differential = @('LedgerTravelBridgeLegacyOracle', 'seed < 64', 'equal(expected, actual)', 'equal(f.requests.get(0), f.requests.get(1))')
    Oracle = @('class LedgerTravelBridgeLegacyOracle', 'upsertExpenseRecord', 'upsertLedgerEntry')
}
foreach ($entry in $reflectionEvidence.GetEnumerator()) {
    if ($reflectionSources.ContainsKey($entry.Key)) {
        foreach ($snippet in $entry.Value) {
            if (-not $reflectionSources[$entry.Key].Contains($snippet)) { $findings.Add("Reflection $($entry.Key) missing evidence: $snippet") | Out-Null }
        }
    }
}
if ($reflectionSources.ContainsKey('Oracle') -and $reflectionSources.Oracle -match 'ReflectTravelLedgerService|LedgerTravelPolicy|LedgerTravelMapper|JpaLedgerTravelAdapter') {
    $findings.Add('Reflection oracle must not call the migrated workflow/policy/mapper/adapter.') | Out-Null
}
$reflectionCi = Get-Content -LiteralPath '.github/workflows/ci.yml' -Raw
foreach ($test in @('TravelLedgerReflectionIntegrationTest', 'LedgerTravelBridgeDifferentialTest', 'ReflectTravelLedgerServiceTest', 'LedgerTravelPolicyTest', 'TravelBridgeEffectsTest')) {
    if (-not $reflectionCi.Contains($test)) { $findings.Add("Reflection CI selection missing: $test") | Out-Null }
}
$ownedReadFiles = @{
    Core = 'backend/src/main/java/com/playdata/calen/travel/application/service/ReadOwnedTravelPlansService.java'
    Transaction = 'backend/src/main/java/com/playdata/calen/travel/adapter/in/transaction/TransactionalOwnedTravelPlans.java'
    Store = 'backend/src/main/java/com/playdata/calen/travel/adapter/out/persistence/JpaOwnedTravelPlansAdapter.java'
    Cache = 'backend/src/main/java/com/playdata/calen/travel/adapter/out/cache/OwnedTravelPlansCache.java'
    Oracle = 'backend/src/test/java/com/playdata/calen/travel/fixture/OwnedTravelPlansLegacyOracle.java'
    Integration = 'backend/src/test/java/com/playdata/calen/travel/OwnedTravelPlansIntegrationTest.java'
}
$ownedReadSources = @{}
foreach ($entry in $ownedReadFiles.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) { $findings.Add("Owned plan read boundary missing: $($entry.Value)") | Out-Null }
    else { $ownedReadSources[$entry.Key] = Get-Content -LiteralPath $entry.Value -Raw }
}
$ownedReadEvidence = @{
    Core = @('store.requireActiveOwner', 'cache.plans', 'cache.portfolio', 'TravelOwnedPlansPolicy.summaries', 'payload.resolve')
    Transaction = @('Propagation.NOT_SUPPORTED', 'summaries.execute', 'TravelSummaryCache.plansKey', 'TravelSummaryCache.portfolioKey', 'reads.setReadOnly(true)')
    Store = @('Propagation.MANDATORY', 'findByIdAndOwnerId', 'summarizeByPlanOwnerId', 'TravelRecordType.LEDGER', 'snapshot(plan, budget, ledger')
    Cache = @('TravelPlanSummaryResponse', 'TravelPortfolioResponse.class', 'ttl > 0', 'summaries.publishIfCurrent')
    Oracle = @('class OwnedTravelPlansLegacyOracle', 'private TravelPortfolioResponse loadPortfolio', 'private List<TravelPlanSummaryResponse> loadPlans')
    Integration = @('jdbc:h2:mem:owned-travel-plans', 'inactiveOwnerFailsBeforeEvenReading', 'sharedRegistryInvalidationPrevents', 'summaryReadsSuspendAnOuterWriteTransaction')
}
foreach ($entry in $ownedReadEvidence.GetEnumerator()) {
    if ($ownedReadSources.ContainsKey($entry.Key)) {
        foreach ($snippet in $entry.Value) {
            if (-not $ownedReadSources[$entry.Key].Contains($snippet)) { $findings.Add("Owned plan read $($entry.Key) missing evidence: $snippet") | Out-Null }
        }
    }
}
if ($ownedReadSources.ContainsKey('Oracle') -and $ownedReadSources.Oracle -match 'ReadOwnedTravelPlansService|TravelOwnedPlansPolicy|TravelOwnedPlansMapper|JpaOwnedTravelPlansAdapter') {
    $findings.Add('Owned plan read oracle must remain independent of the migrated implementation.') | Out-Null
}
foreach ($test in @('OwnedTravelPlansIntegrationTest', 'OwnedTravelPlansDifferentialTest', 'ReadOwnedTravelPlansServiceTest')) {
    if (-not $reflectionCi.Contains($test)) { $findings.Add("Owned plan read CI selection missing: $test") | Out-Null }
}
$referenceReadFiles = @{
    Core = 'backend/src/main/java/com/playdata/calen/travel/application/service/ReadTravelReferenceService.java'
    Transaction = 'backend/src/main/java/com/playdata/calen/travel/adapter/in/transaction/TransactionalTravelReference.java'
    Store = 'backend/src/main/java/com/playdata/calen/travel/adapter/out/persistence/JpaTravelReferenceAdapter.java'
    Rates = 'backend/src/main/java/com/playdata/calen/travel/adapter/out/exchangerate/TravelRateAdapter.java'
    Integration = 'backend/src/test/java/com/playdata/calen/travel/TravelReferenceIntegrationTest.java'
    Oracle = 'backend/src/test/java/com/playdata/calen/travel/fixture/TravelReferenceLegacyOracle.java'
}
$referenceReadSources = @{}
foreach ($entry in $referenceReadFiles.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) { $findings.Add("Reference read boundary missing: $($entry.Value)") | Out-Null }
    else { $referenceReadSources[$entry.Key] = Get-Content -LiteralPath $entry.Value -Raw }
}
$referenceReadEvidence = @{
    Core = @('store.requireActiveOwner', 'store.catalogSource', 'TravelReferencePolicy.catalog', 'TravelReferencePolicy.currencies', 'rates.latest')
    Transaction = @('@Transactional(readOnly = true)', 'implements ReadTravelReferenceUseCase')
    Store = @('Propagation.MANDATORY', 'findAllByPlanOwnerId', 'users.getRequiredUser')
    Rates = @('TravelRatePort, TravelReferenceRatesPort', 'rates.getRequiredRateToKrw', 'rates.getLatestRates', 'new Quote')
    Integration = @('jdbc:h2:mem:travel-reference', 'inactiveOwnerFailsBeforeSourceLookup', 'mandatoryOwnerFactsAndExistingReadOnlyRequiredBoundary', 'malformedCurrenciesFailBeforeProvider')
    Oracle = @('class TravelReferenceLegacyOracle', 'getCategoryCatalog', 'getExchangeRates', 'distinctSorted')
}
foreach ($entry in $referenceReadEvidence.GetEnumerator()) {
    if ($referenceReadSources.ContainsKey($entry.Key)) {
        foreach ($snippet in $entry.Value) {
            if (-not $referenceReadSources[$entry.Key].Contains($snippet)) { $findings.Add("Reference read $($entry.Key) missing evidence: $snippet") | Out-Null }
        }
    }
}
if ($referenceReadSources.ContainsKey('Oracle') -and $referenceReadSources.Oracle -match 'ReadTravelReferenceService|TravelReferencePolicy|TravelReferenceMapper|JpaTravelReferenceAdapter') {
    $findings.Add('Reference read oracle must remain independent of the migrated implementation.') | Out-Null
}
foreach ($test in @('TravelReferenceIntegrationTest', 'TravelReferenceDifferentialTest', 'ReadTravelReferenceServiceTest')) {
    if (-not $reflectionCi.Contains($test)) { $findings.Add("Reference read CI selection missing: $test") | Out-Null }
}
$localReportFiles = @{
    Policy = 'backend/src/main/java/com/playdata/calen/ledger/domain/policy/LedgerLocalReportPolicy.java'
    Mapper = 'backend/src/main/java/com/playdata/calen/ledger/adapter/in/ai/LedgerLocalReportMapper.java'
    Oracle = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerLocalReportLegacyOracle.java'
    Differential = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerLocalReportDifferentialTest.java'
    Tests = 'backend/src/test/java/com/playdata/calen/ledger/domain/policy/LedgerLocalReportPolicyTest.java'
}
$localReportSources = @{}
foreach ($entry in $localReportFiles.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) { $findings.Add("Local report boundary missing: $($entry.Value)") | Out-Null }
    else { $localReportSources[$entry.Key] = Get-Content -LiteralPath $entry.Value -Raw }
}
$localReportEvidence = @{
    Policy = @('buildFallbackReport', 'buildRecurringExpenseCandidates', 'buildExpensePaymentBreakdown', 'Locale.KOREA', '.limit(10)', 'RoundingMode.HALF_UP')
    Mapper = @('LedgerLocalReportData', 'LedgerAnalysisPreparedData.ExpenseEntryPayload', 'new AnalysisDataset', 'new LedgerAiAnalysisReportResponse')
    Oracle = @('class LedgerLocalReportLegacyOracle', 'buildFallbackReport', 'buildRecurringExpenseCandidates', 'PaymentAccumulator', 'RecurringExpenseAccumulator')
    Differential = @('seed < 128', 'old.buildFallbackReport', 'equal(expected, LedgerLocalReportMapper.response(actual))')
    Tests = @('localReportUsesAll500EntriesRatherThanTheProviderRequestCap', 'Collections.nCopies(500', 'recurringIdentityOmitsGroupRetainsFirstDisplayValues')
}
foreach ($entry in $localReportEvidence.GetEnumerator()) {
    if ($localReportSources.ContainsKey($entry.Key)) {
        foreach ($snippet in $entry.Value) {
            if (-not $localReportSources[$entry.Key].Contains($snippet)) { $findings.Add("Local report $($entry.Key) missing evidence: $snippet") | Out-Null }
        }
    }
}
if ($localReportSources.ContainsKey('Oracle') -and $localReportSources.Oracle -match 'LedgerLocalReportPolicy|LedgerLocalReportMapper|LedgerLocalReportData') {
    $findings.Add('Local report oracle must remain independent of the migrated implementation.') | Out-Null
}
if ($localReportSources.ContainsKey('Policy') -and $localReportSources.Policy -match 'LedgerAnalysisPreparedData|ledger\.dto\.|ledger\.ai\.|RestClient|ObjectMapper|LedgerSpendingAnalysisPort') {
    $findings.Add('Local report policy must not depend on DTOs, orchestration, provider clients or adapters.') | Out-Null
}
foreach ($test in @('LedgerLocalReportDifferentialTest', 'LedgerLocalReportPolicyTest', 'LedgerAnalysisBoundaryMapperTest')) {
    if (-not $reflectionCi.Contains($test)) { $findings.Add("Local report CI selection missing: $test") | Out-Null }
}
$localReportServiceTest = Get-Content -LiteralPath 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAiAnalysisServiceTest.java' -Raw
if (-not $localReportServiceTest.Contains('contains("총 205건")')) { $findings.Add('Local report must retain full dataset evidence while provider requests are capped.') | Out-Null }
$preparationFiles = @{
    Policy = 'backend/src/main/java/com/playdata/calen/ledger/domain/policy/LedgerAnalysisCalendarPolicy.java'
    Core = 'backend/src/main/java/com/playdata/calen/ledger/application/service/PrepareLedgerAnalysisDatasetService.java'
    Entry = 'backend/src/main/java/com/playdata/calen/ledger/adapter/in/ai/LedgerAnalysisDatasetPreparation.java'
    Store = 'backend/src/main/java/com/playdata/calen/ledger/adapter/out/persistence/LedgerAnalysisDatasetQueryAdapter.java'
    Oracle = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAnalysisPreparationLegacyOracle.java'
    Differential = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAnalysisPreparationDifferentialTest.java'
    Integration = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAnalysisPreparationIntegrationTest.java'
    Flow = 'backend/src/test/java/com/playdata/calen/ledger/application/service/PrepareLedgerAnalysisDatasetServiceTest.java'
    Calendar = 'backend/src/test/java/com/playdata/calen/ledger/domain/policy/LedgerAnalysisCalendarPolicyTest.java'
}
$preparationSources = @{}
foreach ($entry in $preparationFiles.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) { $findings.Add("Analysis preparation boundary missing: $($entry.Value)") | Out-Null }
    else { $preparationSources[$entry.Key] = Get-Content -LiteralPath $entry.Value -Raw }
}
$preparationEvidence = @{
    Policy = @('request.anchorDate() == null ? today.get()', 'MAX_CUSTOM_RANGE_DAYS = 366', 'comparisonUnitFor', 'comparisonPeriodsFor')
    Core = @('implements PrepareLedgerAnalysisDatasetUseCase', 'TOP_EXPENSE_LIMIT = 20', 'LedgerAiComparisonPreset.WEEKLY_DIGEST', 'store.expenses(owner, comparison)')
    Entry = @('implements PrepareLedgerAnalysisDatasetUseCase', 'delegate.prepare(owner, plan)')
    Store = @('statistics.getOverview', 'findExpenseEntriesForAiAnalysis', 'findTopExpenseEntriesForAiAnalysis', 'PageRequest.of(0, limit)')
    Oracle = @('class LedgerAnalysisPreparationLegacyOracle', 'resolvePlan', 'buildDataset', 'normalizeFocusPrompt')
    Differential = @('isEqualTo(1764)', 'seed < 64', 'oracle.resolvePlan', 'oracle.buildDataset', 'containsExactlyElementsOf(expectedTrace)')
    Integration = @('jdbc:h2:mem:analysis-preparation', 'ownerExpenseAndTrashScope', 'weeklyDigestHasFullExpenses', 'noNewEncompassingTransaction', 'anExistingCallerTransactionIsJoined', 'inactiveOwnerStops')
    Flow = @('fullQueryOrderAndOwnerPropagation', 'weeklyDigestSkipsTop', 'failedPrimaryQueryNeverReadsTopOrComparison')
    Calendar = @('clockIsLazyAfterModeValidation', 'inclusive366DayLimit', 'weekAndHalfYearBoundaries')
}
foreach ($entry in $preparationEvidence.GetEnumerator()) {
    if ($preparationSources.ContainsKey($entry.Key)) {
        foreach ($snippet in $entry.Value) {
            if (-not $preparationSources[$entry.Key].Contains($snippet)) { $findings.Add("Analysis preparation $($entry.Key) missing evidence: $snippet") | Out-Null }
        }
    }
}
if ($preparationSources.ContainsKey('Oracle') -and $preparationSources.Oracle -match 'LedgerAnalysisCalendarPolicy|LedgerAnalysisPreparationMapper|PrepareLedgerAnalysisDatasetService|LedgerAnalysisDatasetQueryAdapter') {
    $findings.Add('Analysis preparation oracle must remain independent of the migrated implementation.') | Out-Null
}
foreach ($key in @('Entry', 'Store')) {
    if ($preparationSources.ContainsKey($key) -and $preparationSources[$key] -match '@Transactional') {
        $findings.Add("Analysis preparation $key must not introduce an encompassing transaction.") | Out-Null
    }
}
foreach ($test in @('LedgerAnalysisPreparationDifferentialTest', 'LedgerAnalysisCalendarPolicyTest', 'PrepareLedgerAnalysisDatasetServiceTest', 'LedgerAnalysisPreparationIntegrationTest')) {
    if (-not $reflectionCi.Contains($test)) { $findings.Add("Analysis preparation CI selection missing: $test") | Out-Null }
}
$recoveryFiles = @{
    Port = 'backend/src/main/java/com/playdata/calen/ledger/application/port/out/LedgerAiRecoveryPort.java'
    Core = 'backend/src/main/java/com/playdata/calen/ledger/application/service/RecoverLedgerAiJobsService.java'
    Sources = 'backend/src/main/java/com/playdata/calen/ledger/adapter/out/jobs/LedgerAiRecoverySources.java'
    Oracle = 'backend/src/test/java/com/playdata/calen/ledger/fixture/LedgerAiRecoveryLegacyOracle.java'
    Differential = 'backend/src/test/java/com/playdata/calen/ledger/adapter/out/jobs/LedgerAiRecoveryDifferentialTest.java'
    Tests = 'backend/src/test/java/com/playdata/calen/ledger/application/service/RecoverLedgerAiJobsServiceTest.java'
    Database = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAiJobPersistenceTest.java'
}
$recoverySources = @{}
foreach ($entry in $recoveryFiles.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) { $findings.Add("Job recovery boundary missing: $($entry.Value)") | Out-Null }
    else { $recoverySources[$entry.Key] = Get-Content -LiteralPath $entry.Value -Raw }
}
$recoveryEvidence = @{
    Port = @('interface IdSource<J>', 'interface AnalysisJob', 'interface ImageJob', 'record WorkbookCursor', 'interface WorkbookSource')
    Core = @('MAX_BATCHES = 5', 'PAGE_SIZE = 100', 'job.running() || job.atCapacity()', 'if (!job.tryReserve()) continue;', 'if (!job.trySubmit()) return;', 'new WorkbookCursor(null, "")')
    Sources = @('LocalDateTime.now().minusMinutes(1)', 'PageRequest.of(0, limit)', 'Duration.ofMinutes(2)', 'Duration.ofMinutes(30)', 'catch (TooManyRequestsException capacity)', 'history.getEffectivePrompt() != null')
    Oracle = @('class LedgerAiRecoveryLegacyOracle', 'recoverAnalysisJobs', 'recoverImageJobs', 'void recover()', 'boolean reserved = false')
    Differential = @('profile < 64', 'analysisRecoveryQueryClockLease', 'imageRecoveryKeepsLegacyInput', 'workbookRecoveryKeepsTheCompositeCursor', 'mockStatic(LocalDateTime.class')
    Tests = @('boundedAnalysisScanResumesBeyond500', 'boundedImageScanResumesBeyond500', 'imageReservationCapacitySkipsOneJob', 'workbookCursorKeepsTimestampAndId')
    Database = @('unrecoverableImageIsFinalizedOutsideTheReadOnlyScan', 'unrecoverableImageStillOwnedByAnotherWorker', 'interruptedAnalysisCanRecoverAfterRestartAndFinish', 'storedImageCanRecoverAfterRestart')
}
foreach ($entry in $recoveryEvidence.GetEnumerator()) {
    if ($recoverySources.ContainsKey($entry.Key)) {
        foreach ($snippet in $entry.Value) {
            if (-not $recoverySources[$entry.Key].Contains($snippet)) { $findings.Add("Job recovery $($entry.Key) missing evidence: $snippet") | Out-Null }
        }
    }
}
if ($recoverySources.ContainsKey('Oracle') -and $recoverySources.Oracle -match 'RecoverLedgerAiJobsService|LedgerAiRecoverySources|LedgerAiRecoveryPort') {
    $findings.Add('Job recovery oracle must remain independent of the migrated implementation.') | Out-Null
}
if ($recoverySources.ContainsKey('Sources') -and $recoverySources.Sources -match '@Transactional|ConcurrentHashMap|SingleFlight') {
    $findings.Add('Job recovery sources must not create new transactions or task/admission registries.') | Out-Null
}
foreach ($test in @('RecoverLedgerAiJobsServiceTest', 'LedgerAiRecoveryDifferentialTest')) {
    if (-not $reflectionCi.Contains($test)) { $findings.Add("Job recovery CI selection missing: $test") | Out-Null }
}
$historyFiles = @{
    Core = 'backend/src/main/java/com/playdata/calen/ledger/application/service/ManageLedgerAnalysisHistoryService.java'
    Store = 'backend/src/main/java/com/playdata/calen/ledger/adapter/out/persistence/LedgerAnalysisHistoryQueryAdapter.java'
    Entry = 'backend/src/main/java/com/playdata/calen/ledger/adapter/in/ai/LedgerAnalysisHistories.java'
    Codec = 'backend/src/main/java/com/playdata/calen/ledger/adapter/out/serialization/LedgerAnalysisHistoryJsonAdapter.java'
    Oracle = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAnalysisHistoryLegacyOracle.java'
    Differential = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAnalysisHistoryDifferentialTest.java'
    Database = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAnalysisHistoryIntegrationTest.java'
    Tests = 'backend/src/test/java/com/playdata/calen/ledger/application/service/ManageLedgerAnalysisHistoryServiceTest.java'
}
$historySources = @{}
foreach ($entry in $historyFiles.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) { $findings.Add("Analysis history boundary missing: $($entry.Value)") | Out-Null }
    else { $historySources[$entry.Key] = Get-Content -LiteralPath $entry.Value -Raw }
}
$historyEvidence = @{
    Core = @('MAX_HISTORY_PAGE_SIZE = 50', 'store.requireActiveOwner(owner)', 'filter.createdTo().plusDays(1)', 'LedgerAiAnalysisStatus.PROCESSING', 'readReusable', 'buildUnreadableHistoryResult', 'catch (RuntimeException failure)', 'now.get()', 'zone.get()')
    Store = @('histories.searchHistories', 'histories.findByIdAndOwnerId(id, owner)', 'findLatestMatchingCompletedAnalysis', 'findLatestMatchingProcessingAnalysis', 'key.provider()', 'key.model()', 'key.createdAfter()', 'Limit.of(1)')
    Entry = @('@Transactional(readOnly = true)', 'public int delete(', 'public Optional<StoredHistory> completed', 'delegate.readReusable')
    Codec = @('codec.readResult(value)', 'LedgerAnalysisHistoryMapper.facts')
    Oracle = @('class LedgerAnalysisHistoryLegacyOracle', 'getHistories', 'findLatestMatchingAnalysis', 'readReusableStoredHistoryResult', 'buildUnreadableHistoryResult')
    Differential = @('seed < 128', 'seed < 96', 'seed < 64', 'old.readStoredHistoryResultOrFallback', 'old.getHistories', 'old.findLatestMatchingAnalysis')
    Database = @('jdbc:h2:mem:analysis-histories', 'wrongOwnerCannotReadOrDelete', 'deletionJoinsAnOuterWriteTransaction', 'crudHasOriginalRequiredTransactions', 'matchingUsesExactProviderModelOwnerDates', 'inactiveOwnerFailsBeforeAnInvalidSearch')
    Tests = @('processingNeverParsesOrDisplays', 'corruptOrEmptyCompletedResults', 'fallbackPreservesSummaryWhitespace', 'internalMatchingDoesNotRevalidate')
}
foreach ($entry in $historyEvidence.GetEnumerator()) {
    if ($historySources.ContainsKey($entry.Key)) {
        foreach ($snippet in $entry.Value) {
            if (-not $historySources[$entry.Key].Contains($snippet)) { $findings.Add("Analysis history $($entry.Key) missing evidence: $snippet") | Out-Null }
        }
    }
}
if ($historySources.ContainsKey('Oracle') -and $historySources.Oracle -match 'ManageLedgerAnalysisHistory|LedgerAnalysisHistoryMapper|LedgerAnalysisHistoryData|LedgerAnalysisHistoryQueryAdapter') {
    $findings.Add('Analysis history oracle must remain independent of the migrated implementation.') | Out-Null
}
if ($historySources.ContainsKey('Store') -and $historySources.Store -match '@Transactional|ConcurrentHashMap|SingleFlight') {
    $findings.Add('Analysis history queries must not create new transactions or caches.') | Out-Null
}
foreach ($test in @('ManageLedgerAnalysisHistoryServiceTest', 'LedgerAnalysisHistoryDifferentialTest', 'LedgerAnalysisHistoryIntegrationTest')) {
    if (-not $reflectionCi.Contains($test)) { $findings.Add("Analysis history CI selection missing: $test") | Out-Null }
}
if (-not $localReportServiceTest.Contains('reusableHistoryStillUsesTheFiveMinuteCutoffAndExactProviderModelKey')) {
    $findings.Add('Analysis history must retain five-minute/provider/model reuse evidence.') | Out-Null
}
$admissionFiles = @{
    Core = 'backend/src/main/java/com/playdata/calen/ledger/application/service/StartLedgerAnalysisService.java'
    Port = 'backend/src/main/java/com/playdata/calen/ledger/application/port/out/LedgerAnalysisAdmissionPort.java'
    Source = 'backend/src/main/java/com/playdata/calen/ledger/adapter/out/jobs/LedgerAnalysisAdmissions.java'
    Drafts = 'backend/src/main/java/com/playdata/calen/ledger/adapter/support/LedgerAnalysisHistoryDrafts.java'
    Oracle = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAnalysisAdmissionLegacyOracle.java'
    Differential = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAnalysisAdmissionDifferentialTest.java'
    Tests = 'backend/src/test/java/com/playdata/calen/ledger/application/service/StartLedgerAnalysisServiceTest.java'
}
$admissionSources = @{}
foreach ($entry in $admissionFiles.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) { $findings.Add("Analysis admission boundary missing: $($entry.Value)") | Out-Null }
    else { $admissionSources[$entry.Key] = Get-Content -LiteralPath $entry.Value -Raw }
}
$admissionEvidence = @{
    Core = @('operation.requireActiveOwner()', 'operation.requireConfigured()', 'operation.now().minus(duplicateWindow)', 'operation.processing(since)', 'operation.reusable(since)', 'operation.submissionFailed(failure)', 'operation.pendingDetail()')
    Port = @('interface AdmissionLease extends AutoCloseable', 'Detail pendingDetail()', 'void createProcessing()')
    Source = @('private AppUser owner', 'private LedgerAiAnalysisHistory pending', 'analysis-admission:', 'DigestUtils.md5DigestAsHex', 'StandardCharsets.UTF_8', 'Duration.ofMinutes(2)', 'pending = histories.save(draft)', 'LedgerAnalysisHistoryMapper.summary(pending)')
    Drafts = @('history.setOwner(owner)', 'history.setFocusPrompt', 'history.setModel(properties.getModel())', 'history.setProvider(aiMetrics.providerLabel())', 'LedgerAnalysisAdmissionPolicy.buildTitle(plan)')
    Oracle = @('class LedgerAnalysisAdmissionLegacyOracle', 'startResolvedAnalysis', 'startAdmittedAnalysis', 'baseHistory', 'buildTitle')
    Differential = @('profile < 128', 'profile < 64', 'modern.toString()', 'oldPlan.toString()', 'synchronousExecutorStyleMutationIsVisibleOnlyAfterSubmission')
    Tests = @('ownerFailureStopsBeforeConfiguration', 'busyAdmissionCanReturnOnlyProcessing', 'customFocusAtBusyAdmission', 'newPendingSummaryIsReadOnlyAfterSubmission', 'submissionFailureFinalizesThenRethrows')
}
foreach ($entry in $admissionEvidence.GetEnumerator()) {
    if ($admissionSources.ContainsKey($entry.Key)) {
        foreach ($snippet in $entry.Value) {
            if (-not $admissionSources[$entry.Key].Contains($snippet)) { $findings.Add("Analysis admission $($entry.Key) missing evidence: $snippet") | Out-Null }
        }
    }
}
if ($admissionSources.ContainsKey('Oracle') -and $admissionSources.Oracle -match 'StartLedgerAnalysisService|LedgerAnalysisAdmissions|LedgerAnalysisHistoryDrafts|LedgerAnalysisAdmissionPolicy|LedgerAnalysisAdmissionData') {
    $findings.Add('Analysis admission oracle must remain independent of the migrated implementation.') | Out-Null
}
if ($admissionSources.ContainsKey('Source') -and $admissionSources.Source -match '@Transactional|ConcurrentHashMap|SingleFlight') {
    $findings.Add('Analysis admission source must not create new transactions or shared registries.') | Out-Null
}
foreach ($test in @('StartLedgerAnalysisServiceTest', 'LedgerAnalysisAdmissionDifferentialTest')) {
    if (-not $reflectionCi.Contains($test)) { $findings.Add("Analysis admission CI selection missing: $test") | Out-Null }
}
$executionFiles = @{
    Core = 'backend/src/main/java/com/playdata/calen/ledger/application/service/ExecuteLedgerAnalysisService.java'
    Entry = 'backend/src/main/java/com/playdata/calen/ledger/adapter/in/ai/LedgerAnalysisJobExecution.java'
    Store = 'backend/src/main/java/com/playdata/calen/ledger/adapter/out/persistence/JpaLedgerAnalysisWorkStore.java'
    Formats = 'backend/src/main/java/com/playdata/calen/ledger/adapter/out/analysis/LedgerAnalysisFormats.java'
    Telemetry = 'backend/src/main/java/com/playdata/calen/ledger/adapter/out/observability/LedgerAnalysisWorkTelemetry.java'
    Oracle = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAnalysisExecutionLegacyOracle.java'
    Differential = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAnalysisExecutionDifferentialTest.java'
    Tests = 'backend/src/test/java/com/playdata/calen/ledger/application/service/ExecuteLedgerAnalysisServiceTest.java'
    Database = 'backend/src/test/java/com/playdata/calen/ledger/ai/LedgerAiJobPersistenceTest.java'
}
$executionSources = @{}
foreach ($entry in $executionFiles.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) { $findings.Add("Analysis execution boundary missing: $($entry.Value)") | Out-Null }
    else { $executionSources[$entry.Key] = Get-Content -LiteralPath $entry.Value -Raw }
}
$executionEvidence = @{
    Core = @('jobs.status(owner, history)', 'prepared.serialize()', 'monitoring.start()', 'provider.analyze(prepared.request())', '!ownsLease.getAsBoolean()', 'formats.serializeResult(response)', 'timer != null && !recorded', 'completed ? "success" : "discarded"')
    Entry = @('@Transactional(propagation = Propagation.NOT_SUPPORTED)', 'delegate.process(owner, history, plan, ownsLease)')
    Store = @('histories.findByIdAndOwnerId(history, owner)', 'histories.saveProcessingPayload(history, owner, payload)')
    Formats = @('travelledger.ledger-ai-analysis.v2', 'PROVIDER_EXPENSE_ENTRY_LIMIT = 200', 'PROVIDER_COMPARISON_ENTRY_LIMIT = 120', 'public String serialize()', 'public LedgerAnalysisData.Request request()', 'LedgerAnalysisHistoryMapper.response(result)')
    Telemetry = @('sample == null ? null', 'metrics.recordAiRequest', 'Ledger AI analysis failed: historyId={}, failureType={}')
    Oracle = @('class LedgerAnalysisExecutionLegacyOracle', 'processAnalysisInBackground', 'buildPayload', 'buildResponse', 'buildFallbackReport')
    Differential = @('profile < 128', 'workerStatusSerializationProviderLease', 'completePreparedV2AndResponseJson', 'Preserves200120ProviderVersus205LocalRows')
    Tests = @('lostCompletionRaceIsDiscarded', 'leaseLossAfterInference', 'disabledTimerStillReceivesSuccess', 'mappingFailureAfterTheTimerStarts', 'responseSerializationFailureCannotPublishCompleted')
    Database = @('directWorkerUseCaseSuspendsTheCallerTransaction', 'directWorkerCannotLoadOrFinalizeAnotherOwnersJob', 'analysisSerializationFailureAfterInferenceIsPersistedAsFailed')
}
foreach ($entry in $executionEvidence.GetEnumerator()) {
    if ($executionSources.ContainsKey($entry.Key)) {
        foreach ($snippet in $entry.Value) {
            if (-not $executionSources[$entry.Key].Contains($snippet)) { $findings.Add("Analysis execution $($entry.Key) missing evidence: $snippet") | Out-Null }
        }
    }
}
if ($executionSources.ContainsKey('Oracle') -and $executionSources.Oracle -match 'ExecuteLedgerAnalysisService|LedgerAnalysisJobExecution|LedgerAnalysisFormats|LedgerAnalysisWorkStore|LedgerAnalysisWorkMonitoring|LedgerAnalysisWorkFormats') {
    $findings.Add('Analysis execution oracle must remain independent of the migrated implementation.') | Out-Null
}
foreach ($name in @('Store', 'Formats')) {
    if ($executionSources.ContainsKey($name) -and $executionSources[$name] -match '@Transactional|ConcurrentHashMap|SingleFlight') {
        $findings.Add("Analysis execution $name must not introduce transactions or registries.") | Out-Null
    }
}
foreach ($test in @('ExecuteLedgerAnalysisServiceTest', 'LedgerAnalysisExecutionDifferentialTest')) {
    if (-not $reflectionCi.Contains($test)) { $findings.Add("Analysis execution CI selection missing: $test") | Out-Null }
}
if ($findings.Count -gt 0) {
    Write-Host 'Service decomposition plan check failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Service decomposition plan check passed.'
