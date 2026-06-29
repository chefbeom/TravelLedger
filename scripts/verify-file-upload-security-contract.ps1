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
$driveStorage = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/drive/service/DriveStorageService.java'
$driveTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/drive/service/DriveStorageServiceTest.java'
$travelStorage = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/service/TravelMediaStorageService.java'
$travelTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/service/TravelMediaStorageServiceTest.java'
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
    'Receipt image exceeds the OCR upload size limit.',
    'Only image files can be analyzed.',
    'normalizeContentType',
    'resolveImageExtension',
    'isAllowedImageContentType',
    'hasAllowedImageSignature',
    'readImageHeader',
    'remoteClient.analyze',
    'invalid_file'
)

Assert-ContainsAll 'LedgerOcrServiceTest' $ocrTest @(
    'analyzeRejectsEmptyFileBeforeRemoteCallOrNotification',
    'analyzeRejectsOversizedFileBeforeRemoteCall',
    'analyzeRejectsImageExtensionWithNonImageMimeBeforeRemoteCall',
    'analyzeRejectsImageMimeWithNonImageExtensionBeforeRemoteCall',
    'analyzeRejectsMismatchedImageMimeAndExtensionBeforeRemoteCall',
    'analyzeRejectsFakeImageBytesBeforeRemoteCall',
    'analyzeRecordsInvalidFileMetricWhenUploadValidationFails',
    'verifyNoInteractions(remoteClient'
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
    'prepareDerivedThumbnailsQuietly',
    'Failed to prepare image thumbnails'
)

Assert-ContainsAll 'TravelMediaStorageServiceTest' $travelTest @(
    'completePresignedUploadRejectsObjectKeyOutsideOwnerRecordScopeBeforeMinioStat',
    'completePresignedUploadRejectsAmbiguousObjectKeyBeforeMinioStat',
    'storesPreparedThumbnailsAlongsideOriginalImage',
    'storesRouteGpxWithoutGeneratingPreparedThumbnails'
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