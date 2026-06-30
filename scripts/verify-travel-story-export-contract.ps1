Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$contractPath = 'docs/travel_story_export.md'
$securityChecklistPath = 'docs/security_baseline_checklist.md'
$roadmapPath = 'docs/project_improvement_roadmap.md'
$mediaContractPath = 'docs/media_processing_queue_contract.md'
$privacyContractPath = 'docs/privacy_control_panel.md'
$ciPath = '.github/workflows/ci.yml'
$controllerPath = 'backend/src/main/java/com/playdata/calen/travel/web/TravelController.java'
$servicePath = 'backend/src/main/java/com/playdata/calen/travel/service/TravelService.java'
$shareVisibilityTestPath = 'backend/src/test/java/com/playdata/calen/travel/service/TravelServiceShareVisibilityTest.java'
$publicMediaTokenTestPath = 'backend/src/test/java/com/playdata/calen/travel/service/TravelPublicMediaTokenServiceTest.java'
$privacyTestPath = 'backend/src/test/java/com/playdata/calen/account/PrivacyControllerIntegrationTest.java'

$findings = [System.Collections.Generic.List[string]]::new()

foreach ($path in @($contractPath, $securityChecklistPath, $roadmapPath, $mediaContractPath, $privacyContractPath, $ciPath, $controllerPath, $servicePath, $shareVisibilityTestPath, $publicMediaTokenTestPath, $privacyTestPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Missing travel story export contract input: $path") | Out-Null
    }
}

if ($findings.Count -eq 0) {
    $contract = Get-Content -LiteralPath $contractPath -Raw
    $securityChecklist = Get-Content -LiteralPath $securityChecklistPath -Raw
    $roadmap = Get-Content -LiteralPath $roadmapPath -Raw
    $mediaContract = Get-Content -LiteralPath $mediaContractPath -Raw
    $privacyContract = Get-Content -LiteralPath $privacyContractPath -Raw
    $ci = Get-Content -LiteralPath $ciPath -Raw
    $controller = Get-Content -LiteralPath $controllerPath -Raw
    $service = Get-Content -LiteralPath $servicePath -Raw
    $shareVisibilityTest = Get-Content -LiteralPath $shareVisibilityTestPath -Raw
    $publicMediaTokenTest = Get-Content -LiteralPath $publicMediaTokenTestPath -Raw
    $privacyTest = Get-Content -LiteralPath $privacyTestPath -Raw

    foreach ($section in @('# Travel Story Export Contract', '## Current baseline', '## Story assembly flow', '## Data contract',
    '## Export product modes',
    '## Story composition checklist', '## Non-negotiable safety rules', '## Current implementation anchors', '## Release gate', '## CI contract')) {
        if (-not $contract.Contains($section)) {
            $findings.Add("Travel story export contract missing section: $section") | Out-Null
        }
    }

    foreach ($phrase in @('read-only', 'owner-scoped', 'recipient-scoped and completed-plan only', 'explicit public sharing and completed status', 'prepared-thumbnail/media-token visibility checks', 'Revoking public travel sharing', 'object storage paths', 'presigned URLs', 'raw GPS/EXIF', 'async bounded job')) {
        if (-not $contract.Contains($phrase)) {
            $findings.Add("Travel story export contract missing required phrase: $phrase") | Out-Null
        }
    }

    foreach ($snippet in @('@AuthenticationPrincipal AppUserPrincipal currentUser', '@GetMapping("/plans/{planId}")', '@GetMapping("/my-map")', '@GetMapping("/public-trips")', '@GetMapping("/shared-exhibits")', 'getPublicTripsOverview(currentUser.userId())', 'getSharedExhibits(currentUser.userId()', 'getPublicTripPhotoClusterDetail(currentUser.userId()')) {
        if (-not $controller.Contains($snippet)) {
            $findings.Add("TravelController missing story/share visibility snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('getPublicTripsOverview', 'getPublicTripPhotoClusterDetail', 'getSharedExhibits', 'getSharedExhibit', 'updatePlanPublicShare', 'getMyMapOverview', 'getMyMapMarkerDetails', 'getMyMapPhotoClusterDetail')) {
        if (-not $service.Contains($snippet)) {
            $findings.Add("TravelService missing story assembly anchor: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('rejectsPublicSharingForIncompletePlan', 'allowsPublicShareDisableForIncompletePlan', 'sharedExhibitListOnlyUsesCompletedPlans', 'rejectsSharedExhibitDetailWhenPlanIsNoLongerCompleted', 'findByIdAndRecipientId', 'TravelPlanStatus.COMPLETED')) {
        if (-not $shareVisibilityTest.Contains($snippet)) {
            $findings.Add("TravelServiceShareVisibilityTest missing visibility evidence: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('issuedTokenMatchesOnlyOriginalMediaId', 'invalidOrMissingTokensDoNotMatch', 'tokenIssuedWithDifferentSecretDoesNotMatch')) {
        if (-not $publicMediaTokenTest.Contains($snippet)) {
            $findings.Add("TravelPublicMediaTokenServiceTest missing token evidence: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('travelPublicMediaShareRevocationRequiresAuthenticationAndCsrf', 'travel public media share revocation', 'public media')) {
        if (-not $privacyTest.Contains($snippet) -and -not $privacyContract.Contains($snippet)) {
            $findings.Add("Privacy controls missing travel public media revocation evidence: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('Data export media queue', 'Large photo/file archive generation', 'Queue payloads must not include raw presigned URLs', 'media export archives')) {
        if (-not $mediaContract.Contains($snippet)) {
            $findings.Add("Media processing contract missing export queue snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('TRAVEL-01', 'Travel story/export', 'docs/travel_story_export.md', 'travel-story-export-contract', 'scripts/verify-travel-story-export-contract.ps1')) {
        if (-not $securityChecklist.Contains($snippet)) {
            $findings.Add("Security baseline missing travel story export snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('Travel timeline/story export',
    'web story, web exhibition',
    'future PDF/static export modes',
    'route timeline, photo map, spending summary, and memories', 'docs/travel_story_export.md', 'travel-story-export-contract', 'scripts/verify-travel-story-export-contract.ps1')) {
        if (-not $roadmap.Contains($snippet)) {
            $findings.Add("Project roadmap missing travel story export snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('travel-story-export-contract:', './scripts/verify-travel-story-export-contract.ps1', '[travel-story-export-contract]="${{ needs[''travel-story-export-contract''].result }}"')) {
        if (-not $ci.Contains($snippet)) {
            $findings.Add("CI workflow missing travel story export contract snippet: $snippet") | Out-Null
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'Travel story export contract verification failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Travel story export contract verification passed.'
