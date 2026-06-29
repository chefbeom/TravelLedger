Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$contractPath = 'docs/media_processing_queue_contract.md'
$securityChecklistPath = 'docs/security_baseline_checklist.md'
$ciPath = '.github/workflows/ci.yml'
$envExamplePath = '.env.example'
$ociEnvExamplePath = '.env.oci.app.example'
$applicationConfigPath = 'backend/src/main/resources/application.yml'
$travelStoragePath = 'backend/src/main/java/com/playdata/calen/travel/service/TravelMediaStorageService.java'
$thumbnailBackfillPath = 'backend/src/main/java/com/playdata/calen/travel/service/TravelThumbnailBackfillService.java'
$imageThumbnailPath = 'backend/src/main/java/com/playdata/calen/common/media/ImageThumbnailService.java'
$travelStorageTestPath = 'backend/src/test/java/com/playdata/calen/travel/service/TravelMediaStorageServiceTest.java'
$thumbnailBackfillTestPath = 'backend/src/test/java/com/playdata/calen/travel/service/TravelThumbnailBackfillServiceTest.java'
$imageThumbnailTestPath = 'backend/src/test/java/com/playdata/calen/common/media/ImageThumbnailServiceTest.java'
$familyAlbumControllerTestPath = 'backend/src/test/java/com/playdata/calen/familyalbum/web/FamilyAlbumControllerTest.java'

$findings = [System.Collections.Generic.List[string]]::new()

foreach ($path in @($contractPath, $securityChecklistPath, $ciPath, $envExamplePath, $ociEnvExamplePath, $applicationConfigPath, $travelStoragePath, $thumbnailBackfillPath, $imageThumbnailPath, $travelStorageTestPath, $thumbnailBackfillTestPath, $imageThumbnailTestPath, $familyAlbumControllerTestPath)) {
    if (-not (Test-Path -LiteralPath $path)) {
        $findings.Add("Missing media processing contract input: $path") | Out-Null
    }
}

if ($findings.Count -eq 0) {
    $contract = Get-Content -LiteralPath $contractPath -Raw
    $securityChecklist = Get-Content -LiteralPath $securityChecklistPath -Raw
    $ci = Get-Content -LiteralPath $ciPath -Raw
    $envExample = Get-Content -LiteralPath $envExamplePath -Raw
    $ociEnvExample = Get-Content -LiteralPath $ociEnvExamplePath -Raw
    $applicationConfig = Get-Content -LiteralPath $applicationConfigPath -Raw
    $travelStorage = Get-Content -LiteralPath $travelStoragePath -Raw
    $thumbnailBackfill = Get-Content -LiteralPath $thumbnailBackfillPath -Raw
    $imageThumbnail = Get-Content -LiteralPath $imageThumbnailPath -Raw
    $travelStorageTest = Get-Content -LiteralPath $travelStorageTestPath -Raw
    $thumbnailBackfillTest = Get-Content -LiteralPath $thumbnailBackfillTestPath -Raw
    $imageThumbnailTest = Get-Content -LiteralPath $imageThumbnailTestPath -Raw
    $familyAlbumControllerTest = Get-Content -LiteralPath $familyAlbumControllerTestPath -Raw

    foreach ($section in @('# Media Processing Queue Contract', '## Current baseline', '## Queue separation target', '## Concrete lane ownership', '## Required invariants', '## Test evidence to keep current', '## Release gate', '## CI contract')) {
        if (-not $contract.Contains($section)) {
            $findings.Add("Media processing contract missing section: $section") | Out-Null
        }
    }

    foreach ($phrase in @('Large media upload queue', 'Thumbnail reprocess queue', 'Video preview queue', 'Data export media queue', 'Original media upload lane', 'Thumbnail backfill/reprocessing lane', 'Future video/transcode lane', 'TRAVEL_PRESIGNED_UPLOAD_ENABLED', 'TRAVEL_THUMBNAIL_BACKFILL_ENABLED', 'TRAVEL_THUMBNAIL_BACKFILL_FIXED_DELAY_MS', 'TRAVEL_THUMBNAIL_BACKFILL_INITIAL_DELAY_MS', 'TRAVEL_THUMBNAIL_BACKFILL_PAGE_SIZE', 'TRAVEL_THUMBNAIL_BACKFILL_MAX_ITEMS_PER_RUN', 'must not process original video/photo uploads on the thumbnail backfill scheduler', 'separate queue/executor/metrics before broad video processing is enabled', 'owner-scoped object keys', 'fail closed', 'idempotent', 'uploaded, processing, ready, failed, and retryable', 'raw presigned URLs, public tokens, API keys, secondary PINs, raw EXIF payloads, or full filesystem paths')) {
        if (-not $contract.Contains($phrase)) {
            $findings.Add("Media processing contract missing required phrase: $phrase") | Out-Null
        }
    }


    foreach ($snippet in @('TRAVEL_THUMBNAIL_BACKFILL_ENABLED=', 'TRAVEL_THUMBNAIL_BACKFILL_FIXED_DELAY_MS=', 'TRAVEL_THUMBNAIL_BACKFILL_INITIAL_DELAY_MS=', 'TRAVEL_THUMBNAIL_BACKFILL_PAGE_SIZE=', 'TRAVEL_THUMBNAIL_BACKFILL_MAX_ITEMS_PER_RUN=', 'TRAVEL_PRESIGNED_UPLOAD_ENABLED=')) {
        if (-not $envExample.Contains($snippet)) {
            $findings.Add("Environment example missing media queue setting: $snippet") | Out-Null
        }
        if (-not $ociEnvExample.Contains($snippet)) {
            $findings.Add("OCI environment example missing media queue setting: $snippet") | Out-Null
        }
    }

    foreach ($snippet in @('thumbnail-backfill-enabled:', 'thumbnail-backfill-fixed-delay-ms:', 'thumbnail-backfill-initial-delay-ms:', 'thumbnail-backfill-page-size:', 'thumbnail-backfill-max-items-per-run:', 'presigned-upload-enabled:')) {
        if (-not $applicationConfig.Contains($snippet)) {
            $findings.Add("Application config missing media queue setting: $snippet") | Out-Null
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