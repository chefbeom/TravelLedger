$ErrorActionPreference = 'Stop'

function Read-RequiredFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Required file missing: $Path"
    }
    return Get-Content -Raw -LiteralPath $Path
}

function Assert-ContainsAll([string]$Name, [string]$Content, [string[]]$Snippets) {
    foreach ($snippet in $Snippets) {
        if (-not $Content.Contains($snippet)) {
            throw "$Name is missing required snippet: $snippet"
        }
    }
}

$contract = Read-RequiredFile 'docs/file_upload_security_contract.md'
$securityChecklist = Read-RequiredFile 'docs/security_baseline_checklist.md'
$roadmap = Read-RequiredFile 'docs/project_improvement_roadmap.md'
$ci = Read-RequiredFile '.github/workflows/ci.yml'
$ocrService = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/ledger/ocr/LedgerOcrService.java'
$ocrTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/ledger/ocr/LedgerOcrServiceTest.java'
$imageExtraction = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/ledger/adapter/out/ai/ImageLedgerExtractionHttpAdapter.java'
$routePayload = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/adapter/out/route/TravelRoutePayloadAdapter.java'
$routePayloadTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/adapter/out/route/TravelRoutePayloadAdapterTest.java'
$driveStorage = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/drive/service/DriveStorageService.java'
$driveTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/drive/service/DriveStorageServiceTest.java'
$travelStorage = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/service/TravelMediaStorageService.java'
$travelTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/service/TravelMediaStorageServiceTest.java'
$ownedMediaTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/TravelMediaRefactoringIntegrationTest.java'
$mediaResponses = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/adapter/in/web/TravelMediaHttpResponses.java'
$mediaResponseTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/adapter/in/web/TravelMediaHttpResponsesTest.java'
$familyStorage = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/familyalbum/service/FamilyMediaStorageService.java'
$supportStorage = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/account/service/SupportInquiryStorageService.java'
$supportTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/account/SupportInquiryIntegrationTest.java'
$familyControllerTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/familyalbum/web/FamilyAlbumControllerTest.java'
$imageThumbnailTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/common/media/ImageThumbnailServiceTest.java'

Assert-ContainsAll 'docs/file_upload_security_contract.md' $contract @(
    '# File Upload Security Contract',
    '## Scope',
    '## Validation pipeline',
    '## Required invariants',
    '## Implementation anchors',
    '## Release gate',
    '## CI contract',
    'OCR receipt upload',
    'CalenDrive upload',
    'Travel media upload',
    'Family album upload',
    'Support inquiry attachment',
    'Validate before external calls',
    'Validate before presign',
    'Validate before object stat',
    'Image processing fails closed',
    'file-upload-security-contract'
)

Assert-ContainsAll 'LedgerOcrService' $ocrService @(
    'private void validateFile(MultipartFile file)',
    '거래 이미지가 AI 분석 업로드 제한 용량을 초과했습니다.',
    'Only image files can be analyzed.',
    'normalizeContentType',
    'resolveImageExtension',
    'isAllowedImageContentType',
    'hasAllowedImageSignature',
    'readImageHeader',
    'LedgerImageExtractionPort imageExtraction',
    'validateFile(file)',
    'imageExtraction.analyze(new MultipartLedgerImageContent(file)',
    'invalid_file'
)

Assert-ContainsAll 'LedgerOcrServiceTest' $ocrTest @(
    'analyzeRejectsEmptyFileBeforeRemoteCall',
    'analyzeRejectsOversizedFileBeforeRemoteCall',
    'analyzeRejectsImageExtensionWithNonImageMimeBeforeRemoteCall',
    'analyzeRejectsImageMimeWithNonImageExtensionBeforeRemoteCall',
    'analyzeRejectsMismatchedImageMimeAndExtensionBeforeRemoteCall',
    'analyzeRejectsFakeImageBytesBeforeRemoteCall',
    'analyzeRecordsInvalidFileMetricWhenUploadValidationFails',
    'verifyNoInteractions(remoteClient'
)

Assert-ContainsAll 'ImageLedgerExtractionHttpAdapter' $imageExtraction @(
    'implements LedgerImageExtractionPort',
    '@Transactional(propagation = Propagation.NOT_SUPPORTED)',
    'requestQueue.execute'
)

Assert-ContainsAll 'TravelRoutePayloadAdapter GPX parser' $routePayload @(
    'implements TravelRoutePayloadPort',
    'parseGpxResource(Resource resource)',
    'factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)',
    'factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)',
    'factory.setFeature("http://xml.org/sax/features/external-general-entities", false)',
    'factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)',
    'factory.setXIncludeAware(false)',
    'factory.setExpandEntityReferences(false)',
    'return decode(routePathJson)'
)

Assert-ContainsAll 'TravelRoutePayloadAdapterTest' $routePayloadTest @(
    'doctypeEntityInputIsRejectedAndManualReadsNeverOpenOriginalFiles',
    'invalidMissingOrOutOfRangeOriginalGpxFallsBackToSnapshot',
    'gpxParsingSkipsNonNumericOrMissingCoordinatesAndClosesItsStream'
)

Assert-ContainsAll 'GPX and OCR runtime checks stay in CI selection' $ci @(
    '--tests com.playdata.calen.ledger.ocr.LedgerOcrServiceTest',
    '--tests com.playdata.calen.ledger.adapter.out.ai.ImageLedgerExtractionHttpAdapterTest',
    '--tests com.playdata.calen.travel.adapter.out.route.TravelRoutePayloadAdapterTest'
)

Assert-ContainsAll 'Owned travel media lifecycle regression' $ownedMediaTest @(
    'foreignOwnerAndWrongRecordKindFailBeforeObjectVerificationOrMutation',
    'secondObjectFailureRollsBackFirstInsertAndNeverLinksOrDeletesUnconfirmedFiles',
    'batchLinksOnlyAfterAllInsertsAndCommitCallbacksAreNotRunOnOuterRollback',
    'downloadAndDeleteEndpointsKeepOwnerCacheIsolationAndImmediateEviction',
    'authenticationCsrfAndNestedBeanValidationRemainEnforced'
)

Assert-ContainsAll 'Travel thumbnail HTTP boundary' $mediaResponses @(
    'thumbnailOnly(Download download, Integer width)',
    'ResponseEntity.notFound()',
    '.cacheControl(CacheControl.noStore())',
    'try (var inputStream = preparedThumbnail.resource().getInputStream())'
)

Assert-ContainsAll 'Travel thumbnail policy distinctions stay explicit' $mediaResponseTest @(
    'genericThumbnailFailureStillFallsBackToOriginalButLoginPreviewNeverDoes',
    'missingOrRuntimeFailingPreviewIsNotFoundWithoutOriginalFallback',
    'successfulLoginPreviewIsNoStoreAndHasNoDispositionHeader'
)

Assert-ContainsAll 'Owned media runtime checks stay in CI selection' $ci @(
    '--tests com.playdata.calen.travel.TravelMediaRefactoringIntegrationTest',
    '--tests com.playdata.calen.travel.adapter.in.web.TravelMediaHttpResponsesTest',
    '--tests com.playdata.calen.travel.adapter.out.storage.TravelMediaStorageAdapterTest',
    '--tests com.playdata.calen.travel.adapter.out.cache.OwnedTravelMediaCacheTest'
)

Assert-ContainsAll 'DriveStorageService' $driveStorage @(
    'EXTENSION_CONTENT_TYPES',
    'GENERIC_CONTENT_TYPES',
    'public List<DriveDtos.UploadChunkResponse> initUpload',
    'prepareUploadRequest',
    'normalizeContentType',
    'validateContentTypeMatchesExtension',
    'File extension and content type do not match.',
    'generateUploadUrl(objectKey)',
    '"drive/" + ownerId + "/" + storedName'
)

Assert-ContainsAll 'DriveStorageServiceTest' $driveTest @(
    'initUploadRejectsKnownExtensionContentTypeMismatchBeforeStorageAccess',
    'initUploadRejectsNullRequestBeforeStorageAccess',
    'initUploadAllowsGenericOctetStreamForKnownExtension',
    'application/octet-stream'
)

Assert-ContainsAll 'TravelMediaStorageService' $travelStorage @(
    'validateUploadCandidates',
    'validateUploadCandidate',
    'validatePreparedThumbnailCandidates',
    'validateCompletedPreparedThumbnailCandidates',
    'validateObjectKey',
    'verifyUploadedObject',
    'validateBinarySignature',
    'Only .gpx files are allowed.',
    'validateRouteGpxObjectKey',
    'private void validateRouteGpxFile',
    'MAX_GPX_FILE_SIZE',
    'prepareDerivedThumbnailsQuietly',
    'Failed to prepare image thumbnails'
)

Assert-ContainsAll 'TravelMediaStorageServiceTest' $travelTest @(
    'completePresignedUploadRejectsObjectKeyOutsideOwnerRecordScopeBeforeMinioStat',
    'completePresignedUploadRejectsAmbiguousObjectKeyBeforeMinioStat',
    'storesPreparedThumbnailsAlongsideOriginalImage',
    'storesRouteGpxWithoutGeneratingPreparedThumbnails',
    'completePresignedGpxRejectsForeignOwnerPlanRouteAndAmbiguousKeysBeforeStat',
    'abortPresignedGpxKeepsOwnerScopeAndExistingBestEffortSequentialOrder',
    'gpxPortBridgeKeepsValidationBeforeLazyReadAndExistingIoErrorTranslation'
)

Assert-ContainsAll 'FamilyMediaStorageService' $familyStorage @(
    'public StoredFamilyMedia store',
    'private DetectedUpload validateFile',
    'detectUpload',
    'File extension and content type must match.',
    'validateBinarySignature',
    'Uploaded file contents do not match the file type.',
    'matchesSignature',
    'buildMinioObjectKey',
    'prepareDerivedThumbnailsQuietly'
)

Assert-ContainsAll 'SupportInquiryStorageService' $supportStorage @(
    'MAX_ATTACHMENT_SIZE_BYTES',
    'ALLOWED_CONTENT_TYPES_BY_EXTENSION',
    'Support attachment must be 5MB or smaller.',
    'resolveAllowedContentType',
    'Support attachment extension and content type must match.',
    'validateBinarySignature',
    'Support attachment binary content does not match its image type.',
    'matchesSignature'
)

Assert-ContainsAll 'SupportInquiryIntegrationTest' $supportTest @(
    'supportAttachmentRejectsSpoofedImageContent',
    'not-a-real-png',
    'Support attachment binary content does not match its image type.'
)

Assert-ContainsAll 'thumbnail fail-closed tests' ($familyControllerTest + $imageThumbnailTest) @(
    'shouldNotFallbackToOriginalImageWhenThumbnailIsUnavailable',
    'shouldCreatePreparedThumbnailsForConfiguredWidths'
)

Assert-ContainsAll 'docs/security_baseline_checklist.md' $securityChecklist @(
    'UPLOAD-01',
    'docs/file_upload_security_contract.md',
    'file-upload-security-contract',
    'scripts/verify-file-upload-security-contract.ps1'
)

Assert-ContainsAll 'docs/project_improvement_roadmap.md' $roadmap @(
    'Upload validation',
    'docs/file_upload_security_contract.md',
    'scripts/verify-file-upload-security-contract.ps1',
    'file-upload-security-contract'
)

Assert-ContainsAll '.github/workflows/ci.yml' $ci @(
    'file-upload-security-contract:',
    './scripts/verify-file-upload-security-contract.ps1',
    '- file-upload-security-contract',
    '[file-upload-security-contract]="${{ needs[''file-upload-security-contract''].result }}"'
)

Write-Host 'file upload security contract verified'
