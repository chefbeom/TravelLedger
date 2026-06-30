Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$privacyDocPath = 'docs/privacy_control_panel.md'
$dataPortabilityDocPath = 'docs/data_portability.md'
$securityChecklistPath = 'docs/security_baseline_checklist.md'
$ciPath = '.github/workflows/ci.yml'
$privacyControllerPath = 'backend/src/main/java/com/playdata/calen/account/web/PrivacyController.java'
$privacyServicePath = 'backend/src/main/java/com/playdata/calen/account/service/PrivacyManagementService.java'
$dataExportServicePath = 'backend/src/main/java/com/playdata/calen/account/service/DataPortabilityExportService.java'
$privacyControllerTestPath = 'backend/src/test/java/com/playdata/calen/account/PrivacyControllerIntegrationTest.java'
$privacyServiceTestPath = 'backend/src/test/java/com/playdata/calen/account/PrivacyManagementServiceTest.java'
$dataExportServiceTestPath = 'backend/src/test/java/com/playdata/calen/account/service/DataPortabilityExportServiceTest.java'

$findings = [System.Collections.Generic.List[string]]::new()

foreach ($path in @($privacyDocPath, $dataPortabilityDocPath, $securityChecklistPath, $ciPath, $privacyControllerPath, $privacyServicePath, $dataExportServicePath, $privacyControllerTestPath, $privacyServiceTestPath, $dataExportServiceTestPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Missing privacy control contract input: $path") | Out-Null
    }
}

if ($findings.Count -eq 0) {
    $privacyDoc = Get-Content -LiteralPath $privacyDocPath -Raw
    $dataPortabilityDoc = Get-Content -LiteralPath $dataPortabilityDocPath -Raw
    $securityChecklist = Get-Content -LiteralPath $securityChecklistPath -Raw
    $ci = Get-Content -LiteralPath $ciPath -Raw
    $privacyController = Get-Content -LiteralPath $privacyControllerPath -Raw
    $privacyService = Get-Content -LiteralPath $privacyServicePath -Raw
    $dataExportService = Get-Content -LiteralPath $dataExportServicePath -Raw
    $privacyControllerTest = Get-Content -LiteralPath $privacyControllerTestPath -Raw
    $privacyServiceTest = Get-Content -LiteralPath $privacyServiceTestPath -Raw
    $dataExportServiceTest = Get-Content -LiteralPath $dataExportServiceTestPath -Raw

    $requiredPrivacySections = @(
        '# Privacy Control Panel Backend Slice',
        '## Implemented Controls',
        '## Privacy management screen contract',
        '## Safety Rules',
        '## Test Evidence',
        '## Contract and CI Gate',
        '## UI Contract Notes',
        'My data download',
        'Revoke all shared links',
        'Delete AI analysis history',
        'Remove photo location metadata',
        'Sensitive cleanup',
        'Privacy screen must not become a secret viewer',
        'aiAnalysisHistoriesDeleted',
        'publicDownloadLinksRevoked',
        'travelPublicMediaSharesRevoked',
        'photoLocationMetadataRemoved',
        'processedAt'
    )
    foreach ($section in $requiredPrivacySections) {
        if (-not $privacyDoc.Contains($section)) {
            $findings.Add("Privacy control doc missing section: $section") | Out-Null
        }
    }

    $requiredPrivacyEndpoints = @(
        'DELETE /api/privacy/ai-analysis-history',
        'DELETE /api/privacy/public-download-links',
        'DELETE /api/privacy/travel-public-media-shares',
        'DELETE /api/privacy/photo-location-metadata',
        'POST /api/privacy/cleanup',
        'POST /api/privacy/data-export'
    )
    foreach ($endpoint in $requiredPrivacyEndpoints) {
        if (-not $privacyDoc.Contains($endpoint)) {
            $findings.Add("Privacy control doc missing endpoint: $endpoint") | Out-Null
        }
        $route = $endpoint -replace '^(DELETE|POST) /api/privacy', ''
        if (-not $privacyController.Contains($route)) {
            $findings.Add("PrivacyController missing route for documented endpoint: $endpoint") | Out-Null
        }
    }

    $requiredPrivacyServiceSnippets = @(
        'deleteAllByOwnerId(userId)',
        'revokeAllActiveByOwnerId(userId, processedAt)',
        'revokePublicSharingByOwnerId(userId)',
        'revokeCommunitySharingByOwnerId(userId)',
        'clearGpsMetadataByPlanOwnerId(userId)',
        'cleanupSensitiveData(Long userId)'
    )
    foreach ($snippet in $requiredPrivacyServiceSnippets) {
        if (-not $privacyService.Contains($snippet)) {
            $findings.Add("PrivacyManagementService missing owner-scoped snippet: $snippet") | Out-Null
        }
    }

    $requiredControllerSnippets = @(
        'SecondaryPinSessionSupport',
        'getVerifiedSecondaryPin(httpRequest)',
        'exportUserDataArchive(currentUser.userId()',
        'new BadRequestException("A verified secondary PIN session is required before exporting data.")'
    )
    foreach ($snippet in $requiredControllerSnippets) {
        if (-not $privacyController.Contains($snippet)) {
            $findings.Add("PrivacyController missing export protection snippet: $snippet") | Out-Null
        }
    }

    $requiredExportSnippets = @(
        'ensureSecondaryPinMatches(user, secondaryPin)',
        'exportEntriesCsv(userId, from, to)',
        'findAllByOwner_IdOrderByLastModifiedAtDesc(userId)',
        'findAllByPlanOwnerIdOrderByUploadedAtDescIdDesc(userId)',
        'findAllByOwnerIdOrderByUploadedAtDescIdDesc(userId)',
        'manifest/drive-items.json',
        'manifest/travel-media.json',
        'manifest/family-media.json',
        'createPasswordProtectedZip',
        'storedObjectPathsIncluded',
        'signedUrlsIncluded',
        'publicTokensIncluded',
        'hasGpsMetadata',
        'excludedFields'
    )
    foreach ($snippet in $requiredExportSnippets) {
        if (-not $dataExportService.Contains($snippet)) {
            $findings.Add("DataPortabilityExportService missing archive safety snippet: $snippet") | Out-Null
        }
    }

    $requiredDataDocSnippets = @(
        'manifest/drive-items.json',
        'manifest/travel-media.json',
        'manifest/family-media.json',
        'hasGpsMetadata',
        'raw latitude/longitude',
        'Secret exclusion',
        'object storage paths',
        'presigned URLs',
        '## CI contract'
    )
    foreach ($snippet in $requiredDataDocSnippets) {
        if (-not $dataPortabilityDoc.Contains($snippet)) {
            $findings.Add("Data portability doc missing privacy snippet: $snippet") | Out-Null
        }
    }

    $requiredTestSnippets = @(
        'aiAnalysisHistoryDeletionRequiresAuthenticationAndCsrf',
        'publicDownloadLinkRevocationRequiresAuthenticationAndCsrf',
        'travelPublicMediaShareRevocationRequiresAuthenticationAndCsrf',
        'photoLocationMetadataRemovalRequiresAuthenticationAndCsrf',
        'sensitiveCleanupRequiresAuthenticationAndCsrf',
        'dataExportRequiresAuthenticationCsrfAndVerifiedSecondaryPin'
    )
    foreach ($snippet in $requiredTestSnippets) {
        if (-not $privacyControllerTest.Contains($snippet)) {
            $findings.Add("PrivacyControllerIntegrationTest missing contract test snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('revokePublicDownloadLinksScopesUpdateToCurrentOwner', 'revokeTravelPublicMediaSharesScopesPlanAndCommunityRecordUpdatesToCurrentOwner', 'removePhotoLocationMetadataScopesGpsCleanupToCurrentOwner', 'cleanupSensitiveDataDeletesAiHistoryAndRevokesOnlyCurrentOwnerShares')) {
        if (-not $privacyServiceTest.Contains($snippet)) {
            $findings.Add("PrivacyManagementServiceTest missing owner-scope evidence snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('exportUserDataArchiveBuildsEncryptedArchiveWithoutOperationalSecrets', 'exportUserDataArchiveVerifiesSecondaryPinBeforeExportingLedgerData')) {
        if (-not $dataExportServiceTest.Contains($snippet)) {
            $findings.Add("DataPortabilityExportServiceTest missing export safety evidence snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('privacy-control-contract', 'scripts/verify-privacy-control-contract.ps1', 'privacy controls delete/revoke/export only the current user data')) {
        if (-not $securityChecklist.Contains($snippet)) {
            $findings.Add("Security baseline missing privacy contract snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('privacy-control-contract:', './scripts/verify-privacy-control-contract.ps1', '[privacy-control-contract]="${{ needs[''privacy-control-contract''].result }}"')) {
        if (-not $ci.Contains($snippet)) {
            $findings.Add("CI workflow missing privacy control contract snippet: $snippet") | Out-Null
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'Privacy control contract verification failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Privacy control contract verification passed.'

