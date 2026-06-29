Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$contractPath = 'docs/media_processing_queue_contract.md'
$securityChecklistPath = 'docs/security_baseline_checklist.md'
$ciPath = '.github/workflows/ci.yml'
$travelStoragePath = 'backend/src/main/java/com/playdata/calen/travel/service/TravelMediaStorageService.java'
$thumbnailBackfillPath = 'backend/src/main/java/com/playdata/calen/travel/service/TravelThumbnailBackfillService.java'
$imageThumbnailPath = 'backend/src/main/java/com/playdata/calen/common/media/ImageThumbnailService.java'
$travelStorageTestPath = 'backend/src/test/java/com/playdata/calen/travel/service/TravelMediaStorageServiceTest.java'
$thumbnailBackfillTestPath = 'backend/src/test/java/com/playdata/calen/travel/service/TravelThumbnailBackfillServiceTest.java'
$imageThumbnailTestPath = 'backend/src/test/java/com/playdata/calen/common/media/ImageThumbnailServiceTest.java'
$familyAlbumControllerTestPath = 'backend/src/test/java/com/playdata/calen/familyalbum/web/FamilyAlbumControllerTest.java'

$findings = [System.Collections.Generic.List[string]]::new()

foreach ($path in @($contractPath, $securityChecklistPath, $ciPath, $travelStoragePath, $thumbnailBackfillPath, $imageThumbnailPath, $travelStorageTestPath, $thumbnailBackfillTestPath, $imageThumbnailTestPath, $familyAlbumControllerTestPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Missing media processing contract input: $path") | Out-Null
    }
}

if ($findings.Count -eq 0) {
    $contract = Get-Content -LiteralPath $contractPath -Raw
    $securityChecklist = Get-Content -LiteralPath $securityChecklistPath -Raw
    $ci = Get-Content -LiteralPath $ciPath -Raw
    $travelStorage = Get-Content -LiteralPath $travelStoragePath -Raw
    $thumbnailBackfill = Get-Content -LiteralPath $thumbnailBackfillPath -Raw
    $imageThumbnail = Get-Content -LiteralPath $imageThumbnailPath -Raw
    $travelStorageTest = Get-Content -LiteralPath $travelStorageTestPath -Raw
    $thumbnailBackfillTest = Get-Content -LiteralPath $thumbnailBackfillTestPath -Raw
    $imageThumbnailTest = Get-Content -LiteralPath $imageThumbnailTestPath -Raw
    $familyAlbumControllerTest = Get-Content -LiteralPath $familyAlbumControllerTestPath -Raw

    foreach ($section in @('# Media Processing Queue Contract', '## Current baseline', '## Queue separation target', '## Required invariants', '## Test evidence to keep current', '## Release gate', '## CI contract')) {
        if (-not $contract.Contains($section)) {
            $findings.Add("Media processing contract missing section: $section") | Out-Null
        }
    }

    foreach ($phrase in @('Large media upload queue', 'Thumbnail reprocess queue', 'Video preview queue', 'Data export media queue', 'owner-scoped object keys', 'fail closed', 'idempotent', 'uploaded, processing, ready, failed, and retryable', 'raw presigned URLs, public tokens, API keys, secondary PINs, raw EXIF payloads, or full filesystem paths')) {
        if (-not $contract.Contains($phrase)) {
            $findings.Add("Media processing contract missing required phrase: $phrase") | Out-Null
        }
    }

    foreach ($snippet in @('preparePresignedUploads', 'completePresignedUpload', 'validateObjectKey', 'validatePreparedThumbnailCandidates', 'validateCompletedPreparedThumbnailCandidates', 'verifyPreparedThumbnailUploads', 'ThumbnailPreparationStatus', 'loadPreparedThumbnail', 'ensurePreparedThumbnails')) {
        if (-not $travelStorage.Contains($snippet)) {
            $findings.Add("TravelMediaStorageService missing media contract snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('app.travel.thumbnail-backfill-enabled', 'thumbnailBackfillPageSize', 'thumbnailBackfillMaxItemsPerRun', 'backfillRunning.compareAndSet(false, true)', 'findAllByIdGreaterThanAndContentTypeStartingWithOrderByIdAsc', 'ensurePreparedThumbnails', 'BackfillRunSummary')) {
        if (-not $thumbnailBackfill.Contains($snippet)) {
            $findings.Add("TravelThumbnailBackfillService missing bounded worker snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('createPreparedThumbnails', 'Failed to create prepared thumbnails.', 'PreparedThumbnailContent', 'writeToCache', 'applyExifOrientation')) {
        if (-not $imageThumbnail.Contains($snippet)) {
            $findings.Add("ImageThumbnailService missing prepared thumbnail snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('storesPreparedThumbnailsAlongsideOriginalImage', 'storesRouteGpxWithoutGeneratingPreparedThumbnails', 'ensurePreparedThumbnailsReportsAlreadyPresentWhenPreparedSetExists', 'ensurePreparedThumbnailsRecreatesMissingPreparedVariant')) {
        if (-not $travelStorageTest.Contains($snippet)) {
            $findings.Add("TravelMediaStorageServiceTest missing evidence snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('runBackfillPassNowProcessesConfiguredImageBatchAndCountsStatuses', 'runBackfillPassNowSkipsRepositoryWhenDisabled')) {
        if (-not $thumbnailBackfillTest.Contains($snippet)) {
            $findings.Add("TravelThumbnailBackfillServiceTest missing evidence snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('shouldCreatePreparedThumbnailsForConfiguredWidths', 'shouldReuseCachedThumbnailForRepeatedRequests')) {
        if (-not $imageThumbnailTest.Contains($snippet)) {
            $findings.Add("ImageThumbnailServiceTest missing evidence snippet: $snippet") | Out-Null
        }
    }

    if (-not $familyAlbumControllerTest.Contains('shouldNotFallbackToOriginalImageWhenThumbnailIsUnavailable')) {
        $findings.Add('FamilyAlbumControllerTest missing fail-closed thumbnail evidence snippet.') | Out-Null
    }

    foreach ($snippet in @('media-processing-contract', 'docs/media_processing_queue_contract.md', 'scripts/verify-media-processing-contract.ps1')) {
        if (-not $securityChecklist.Contains($snippet)) {
            $findings.Add("Security baseline missing media processing contract snippet: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('media-processing-contract:', './scripts/verify-media-processing-contract.ps1', '[media-processing-contract]="${{ needs[''media-processing-contract''].result }}"')) {
        if (-not $ci.Contains($snippet)) {
            $findings.Add("CI workflow missing media processing contract snippet: $snippet") | Out-Null
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host 'Media processing contract verification failed.'
    $findings | Sort-Object -Unique | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host 'Media processing contract verification passed.'