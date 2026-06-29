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

$contract = Read-RequiredFile 'docs/public_share_authorization_contract.md'
$driveShareDoc = Read-RequiredFile 'docs/drive_share_permissions.md'
$securityChecklist = Read-RequiredFile 'docs/security_baseline_checklist.md'
$roadmap = Read-RequiredFile 'docs/project_improvement_roadmap.md'
$ci = Read-RequiredFile '.github/workflows/ci.yml'
$downloadLinkService = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/drive/service/DriveDownloadLinkService.java'
$accessLogService = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/drive/service/DriveDownloadLinkAccessLogService.java'
$downloadLinkTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/drive/service/DriveDownloadLinkServiceTest.java'
$accessLogTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/drive/service/DriveDownloadLinkAccessLogServiceTest.java'
$driveShareService = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/drive/service/DriveShareService.java'
$driveShareTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/drive/service/DriveShareServiceTest.java'
$travelTokenService = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/service/TravelPublicMediaTokenService.java'
$travelTokenTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/service/TravelPublicMediaTokenServiceTest.java'
$travelService = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/service/TravelService.java'
$travelIntegrationTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/TravelPlanUserScopeIntegrationTest.java'
$privacyService = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/account/service/PrivacyManagementService.java'
$privacyTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/account/PrivacyManagementServiceTest.java'
$privacyIntegrationTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/account/PrivacyControllerIntegrationTest.java'

Assert-ContainsAll 'docs/public_share_authorization_contract.md' $contract @(
    '# Public Share Authorization Contract',
    '## Scope',
    '## Authorization flow',
    '## Required invariants',
    '## Implementation anchors',
    '## Release gate',
    '## CI contract',
    'CalenDrive public download link',
    'CalenDrive direct share',
    'Travel public media token',
    'Travel shared exhibit media',
    'Privacy revocation',
    'token fingerprints',
    'VIEW',
    'DOWNLOAD',
    'EDIT',
    'public-share-authorization-contract'
)

Assert-ContainsAll 'docs/drive_share_permissions.md' $driveShareDoc @(
    '# CalenDrive Share Permissions',
    'VIEW',
    'DOWNLOAD',
    'EDIT',
    'Direct shared download URL requests write an owner-scoped audit log',
    'GET /api/file/share/{fileId}/access-logs'
)

Assert-ContainsAll 'DriveDownloadLinkService' $downloadLinkService @(
    'DEFAULT_EXPIRES_IN_MINUTES',
    'MIN_EXPIRES_IN_MINUTES',
    'MAX_EXPIRES_IN_MINUTES',
    'DEFAULT_MAX_DOWNLOADS',
    'createLink',
    'revokeLink',
    'listAccessLogs',
    'downloadByToken',
    'resolveDownloadUrlByToken',
    'resolveAvailableDownloadLink',
    'recordPublicDownloadLinkRequest',
    'recordPublicDownloadLinkAccess',
    'invalid',
    'revoked',
    'expired',
    'limit_reached',
    'invalid_item',
    'trashed',
    'success'
)

Assert-ContainsAll 'DriveDownloadLinkAccessLogService' $accessLogService @(
    'record(',
    'recordDirectShareAccess',
    'listRecentLogs',
    'listRecentDirectShareLogs',
    'tokenFingerprint',
    'clientAddress',
    'userAgent',
    'shared_'
)

Assert-ContainsAll 'DriveDownloadLinkServiceTest' $downloadLinkTest @(
    'downloadByTokenRejectsRevokedLinkWithoutLoadingFile',
    'downloadByTokenRejectsMissingLinkWithoutLoadingFile',
    'downloadByTokenRejectsTrashedFileWithoutLoadingFile',
    'downloadByTokenRejectsDownloadLimitWithoutLoadingFile',
    'listAccessLogsRejectsNonOwnerBeforeReadingLogs',
    'revokeLinkReturnsUnavailableLink',
    'record(eq(7L), eq(11L), eq(1L), eq("public-token")'
)

Assert-ContainsAll 'DriveDownloadLinkAccessLogServiceTest' $accessLogTest @(
    'recordStoresTokenFingerprintWithoutRawToken',
    'recordDirectShareAccessUsesSyntheticFingerprintAndScopedStatus',
    'doesNotContain(rawToken)',
    'shared_permission_denied'
)

Assert-ContainsAll 'DriveShareService' $driveShareService @(
    'shareFiles',
    'listSharedFileAccessLogs',
    'getSharedFileDownloadUrl',
    'downloadSharedFile',
    'ensureDownloadAllowed',
    'recordDirectShareAccess',
    'permission_denied',
    'not_found',
    'unavailable',
    'success',
    'normalizePermission',
    'Unsupported share permission. Use VIEW, DOWNLOAD, or EDIT.'
)

Assert-ContainsAll 'DriveShareServiceTest' $driveShareTest @(
    'shareFilesStoresRequestedViewPermission',
    'downloadSharedFileRejectsViewOnlyShareWithoutLoadingObject',
    'sharedDownloadUrlRecordsSuccessfulAccess',
    'sharedDownloadUrlRecordsViewOnlyDeniedAccess',
    'Shared file permission does not allow download.',
    'recordDirectShareAccess(31L, 7L, 1L, 2L, "permission_denied"',
    'recordDirectShareAccess(null, 7L, null, 3L, "not_found"'
)

Assert-ContainsAll 'TravelPublicMediaTokenService' $travelTokenService @(
    'public String issueToken(Long mediaId)',
    'public boolean matches(Long mediaId, String token)',
    'MessageDigest.isEqual',
    'token.trim()'
)

Assert-ContainsAll 'TravelPublicMediaTokenServiceTest' $travelTokenTest @(
    'issuedTokenMatchesOnlyOriginalMediaId',
    'invalidOrMissingTokensDoNotMatch',
    'tokenIssuedWithDifferentSecretDoesNotMatch',
    'tampered',
    'different-public-media-secret'
)

Assert-ContainsAll 'TravelService public share checks' $travelService @(
    'getSharedMediaDownload(Long mediaId, String token)',
    'travelPublicMediaTokenService.matches(mediaId, token)',
    'Shared media not found.',
    'isMemoryRecord(record)',
    'getSharedExhibitMediaDownload(Long userId, Long shareId, Long mediaId)',
    'TravelPlanShare share = getRequiredShare(userId, shareId)',
    'isCompletedPlan(share.getPlan())',
    'mediaAsset.getPlan().getId().equals(share.getPlan().getId())'
)

Assert-ContainsAll 'TravelPlanUserScopeIntegrationTest' $travelIntegrationTest @(
    'PUBLIC_MEDIA_TOKEN_PATTERN',
    '/api/travel/public/media/{mediaId}/content',
    'invalid-token',
    '/api/travel/shared-exhibits/{shareId}/media/{mediaId}/content'
)

Assert-ContainsAll 'Privacy revocation' ($privacyService + $privacyTest + $privacyIntegrationTest) @(
    'revokePublicDownloadLinks',
    'revokeAllActiveByOwnerId',
    'revokeTravelPublicMediaSurfaces',
    'revokePublicSharingByOwnerId',
    'revokeCommunitySharingByOwnerId',
    'publicDownloadLinkRevocationRequiresAuthenticationAndCsrf',
    'travelPublicMediaShareRevocationRequiresAuthenticationAndCsrf',
    'cleanupSensitiveDataDeletesAiHistoryAndRevokesOnlyCurrentOwnerShares'
)

Assert-ContainsAll 'docs/security_baseline_checklist.md' $securityChecklist @(
    'SHARE-01',
    'docs/public_share_authorization_contract.md',
    'public-share-authorization-contract',
    'scripts/verify-public-share-authorization-contract.ps1'
)

Assert-ContainsAll 'docs/project_improvement_roadmap.md' $roadmap @(
    'Admin/share authorization tests',
    'docs/public_share_authorization_contract.md',
    'scripts/verify-public-share-authorization-contract.ps1',
    'public-share-authorization-contract'
)

Assert-ContainsAll '.github/workflows/ci.yml' $ci @(
    'public-share-authorization-contract:',
    './scripts/verify-public-share-authorization-contract.ps1',
    '- public-share-authorization-contract',
    '[public-share-authorization-contract]="${{ needs[''public-share-authorization-contract''].result }}"'
)

Write-Host 'public share authorization contract verified'