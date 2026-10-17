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
$atlasReads = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/application/service/ReadTravelAtlasService.java'
$atlasStore = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/adapter/out/persistence/JpaTravelAtlasAdapter.java'
$atlasPolicy = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/domain/policy/TravelAtlasPolicy.java'
$atlasTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/TravelAtlasRefactoringIntegrationTest.java'
$atlasDifferential = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/adapter/support/TravelAtlasDifferentialTest.java'
$atlasOracle = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/adapter/support/TravelAtlasLegacyOracle.java'
$mapLinks = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/application/service/TravelMapLinksService.java'
$mapLinkPolicy = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/domain/policy/TravelMapLinkPolicy.java'
$mapLinkStore = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/adapter/out/persistence/JpaTravelMapLinkAdapter.java'
$mapLinkRuntime = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/adapter/out/security/TravelMapLinkRuntime.java'
$mapLinkTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/TravelMapLinkRefactoringIntegrationTest.java'
$mapLinkDifferential = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/adapter/support/TravelMapLinkDifferentialTest.java'
$mapLinkOracle = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/adapter/support/TravelMapLinkLegacyOracle.java'
$exhibitReads = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/application/service/ReadTravelExhibitsService.java'
$exhibitStore = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/adapter/out/persistence/JpaTravelExhibitReadAdapter.java'
$exhibitPolicy = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/domain/policy/TravelExhibitPolicy.java'
$exhibitToken = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/adapter/out/security/TravelPublicMediaTokenAdapter.java'
$exhibitTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/TravelExhibitReadRefactoringIntegrationTest.java'
$exhibitProjectionTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/adapter/support/TravelPlanDetailMapperTest.java'
$exhibitLegacyOracle = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/adapter/support/TravelPlanDetailLegacyOracle.java'
$travelSharing = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/application/service/ManageTravelSharingService.java'
$travelSharingStore = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/adapter/out/persistence/JpaTravelSharingAdapter.java'
$travelSharingPolicy = Read-RequiredFile 'backend/src/main/java/com/playdata/calen/travel/domain/policy/TravelSharingPolicy.java'
$travelSharingTest = Read-RequiredFile 'backend/src/test/java/com/playdata/calen/travel/TravelSharingRefactoringIntegrationTest.java'
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
    'app.security.public-media-key',
    'rotating `app.security.public-media-key` revokes previously issued stateless URLs',
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
    'resolveDownloadUrlByTokenRejectsExpiredLinkWithoutGeneratingPresignedUrl',
    'generateDownloadUrl(anyString(), anyString(), anyString())',    'listAccessLogsRejectsNonOwnerBeforeReadingLogs',
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
    'recordDirectShareAccess(31L, 7L, 1L, 2L, "success", metadata)'
)

Assert-ContainsAll 'TravelPublicMediaTokenService' $travelTokenService @(
    'public String issueToken(Long mediaId)',
    'public boolean matches(Long mediaId, String token)',
    'MessageDigest.isEqual',
    'app.security.public-media-key',
    'token.trim()'
)

Assert-ContainsAll 'TravelPublicMediaTokenServiceTest' $travelTokenTest @(
    'issuedTokenMatchesOnlyOriginalMediaId',
    'invalidOrMissingTokensDoNotMatch',
    'tokenIssuedWithDifferentSecretDoesNotMatch',
    'rotatingPublicMediaKeyRevokesPreviouslyIssuedToken',
    'travel-public-media-key-v1',
    'travel-public-media-key-v2',
    'tampered',
    'different-public-media-secret'
)

Assert-ContainsAll 'TravelService public share compatibility delegates' $travelService @(
    'getSharedMediaDownload(Long mediaId, String token)',
    'getSharedExhibitMediaDownload(Long userId, Long shareId, Long mediaId)',
    'exhibitReads.publicMedia(mediaId, token)',
    'exhibitReads.receivedMedia(userId, shareId, mediaId)'
)

Assert-ContainsAll 'Pure exhibit read authorization' $exhibitReads @(
    'if (!tokens.matches(mediaId, token))',
    'store.receivedShare(viewer, share)',
    'TravelExhibitPolicy.completed(access.plan().status())',
    'media.planId().equals(access.plan().id())',
    'TravelExhibitPolicy.publicMedia(media)',
    'Shared media not found.',
    'Shared exhibit media not found.',
    'store.ownedPlan(owner, plan)'
)

Assert-ContainsAll 'Exhibit visibility policy' $exhibitPolicy @(
    'record == TravelRecordType.MEMORY && media == TravelMediaType.PHOTO',
    'media.community() || (media.publicPlan() && completed(media.status()))'
)

Assert-ContainsAll 'Exhibit read persistence scope' $exhibitStore @(
    '@Transactional(propagation = Propagation.MANDATORY)',
    'findByIdAndRecipientId(id, viewer)',
    'findAllByRecipientIdAndPlan_StatusOrderByCreatedAtDescIdDesc(viewer, TravelPlanStatus.COMPLETED',
    'users.getRequiredUser(viewer)',
    'findByIdAndOwnerId(id, owner)'
)

Assert-ContainsAll 'Public token adapter keeps existing pair-bound HMAC validation' $exhibitToken @(
    'implements TravelPublicMediaTokenPort',
    'tokens.matches(media, token)'
)

Assert-ContainsAll 'Exhibit read regression suites remain selected' $ci @(
    '--tests com.playdata.calen.travel.service.RecordSharedTravelAccessTest',
    '--tests com.playdata.calen.travel.TravelExhibitReadRefactoringIntegrationTest',
    '--tests com.playdata.calen.travel.application.service.ReadTravelExhibitsServiceTest',
    '--tests com.playdata.calen.travel.adapter.out.persistence.JpaTravelExhibitReadAdapterTest',
    '--tests com.playdata.calen.travel.adapter.support.TravelPlanDetailMapperTest'
)

Assert-ContainsAll 'Exhibit read runtime scenarios' $exhibitTest @(
    'recipientMediaIncludesReceiptsButRejectsForeignPlansAndStopsAfterCancel',
    'tokenIsPairBoundAndVisibilityIsRecheckedAfterEachSuccessfulDownload',
    'validTokensNeverExposeLedgerPhotosOrReceiptMediaEvenIfMarkedPublic',
    'trustedRecordBridgeRetainsOwnerScopeAndDoesNotRequireCompletedOrPublicPlans',
    'recordShareHttpRequiresAcceptanceKeepsPrivateThumbnailsAndRevokesAfterCancel',
    'removingCurrentGroupMembershipBlocksAcceptedRecordMediaEvenWhenOwnerMetadataIsCached',
    'authenticationInactiveViewerAndMandatoryAdapterRulesRemainEnforced'
)

Assert-ContainsAll 'Detailed projection full JSON comparison' $exhibitProjectionTest @(
    'completeJsonMatchesFrozenLegacyAssemblyAcrossGeneratedDetailShapes',
    'TravelPlanDetailLegacyOracle.expected',
    'mapper.writeValueAsString(expected)'
)

Assert-ContainsAll 'Frozen detail oracle stays independent of the new projector' $exhibitLegacyOracle @(
    'Frozen DTO assembly from HEAD 4aeb6d7d',
    'return new TravelPlanDetailResponse(',
    'return new TravelMediaResponse('
)

Assert-ContainsAll 'Travel sharing management scope and transaction boundary' $travelSharingStore @(
    'implements TravelSharingStore',
    '@Transactional(propagation = Propagation.MANDATORY)',
    'findByIdAndOwnerId(id, owner)',
    'findByIdAndPlanIdAndSharedById(id, plan, owner)',
    'findAllByOwnerIdOrderByUpdatedAtDescIdDesc(owner)',
    'findByOwnerIdAndNameIgnoreCase(owner, name)',
    'members.flush()'
)

Assert-ContainsAll 'Travel sharing management workflow' $travelSharing @(
    'implements ManageTravelSharingUseCase',
    'store.ownedPlan(owner, planId)',
    'TravelSharingPolicy.requireCompleted(plan.status())',
    'recipients.putIfAbsent(recipient.loginId(), recipient)',
    'summaries.invalidateOwner(owner)',
    'summaries.invalidatePublic()'
)

Assert-ContainsAll 'Travel sharing completion policy' $travelSharingPolicy @(
    'TravelPlanPolicy.status(status) != TravelPlanStatus.COMPLETED',
    '완성된 여행만 다른 사용자에게 공유할 수 있습니다.'
)

Assert-ContainsAll 'TravelSharingRefactoringIntegrationTest' $travelSharingTest @(
    'groupHttpCreateListReplaceAndDeleteKeepOwnerScopeMemberOrderAndJson',
    'invalidRecipientDoesNotClearExistingMembersAndInactiveOrSelfCannotBeAdded',
    'outerRollbackRestoresMembershipAfterTheActualDeleteFlushAndReplacement',
    'shareHttpReusesExistingRowListsOnlyOwnedSharesAndCancelRevokesTheRecipientView',
    'shareCreateAndCancelRollbackKeepTheRowsAndOnlyImmediateCacheEviction',
    'publicToggleRollbackAndCacheFailureCannotCommitTheVisibilityChange',
    'authenticationCsrfAndRequestValidationRemainOnAllMovedRoutes'
)

Assert-ContainsAll 'Sharing management runtime checks stay in CI' $ci @(
    '--tests com.playdata.calen.travel.TravelSharingRefactoringIntegrationTest',
    '--tests com.playdata.calen.travel.application.service.ManageTravelSharingServiceTest',
    '--tests com.playdata.calen.travel.adapter.support.TravelSharingMapperTest'
)

Assert-ContainsAll 'TravelPlanUserScopeIntegrationTest' $travelIntegrationTest @(
    'PUBLIC_MEDIA_URL_PATTERN.matcher(heroPhotoUrl)',
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

Assert-ContainsAll 'Bearer map-link authorization workflow' $mapLinks @(
    'store.requireActiveOwner(owner)',
    'store.ownedPlans(owner, plans)',
    'store.ownedRecords(owner, planSet, records)',
    'store.ownedMedia(owner, planSet, media)',
    'store.ownedRoutes(owner, planSet, routes)',
    'store.active(normalized)',
    'if (!visible(asset, plans, link.scope()))',
    'store.photos(new HashSet<>(plans(link)), link.scope())'
)
Assert-ContainsAll 'Bearer map-link media scope policy' $mapLinkPolicy @(
    'plans.contains(asset.planId())',
    'asset.mediaType() == TravelMediaType.PHOTO',
    'asset.recordType() == TravelRecordType.MEMORY',
    '!scope.records().contains(asset.recordId())',
    '!scope.media().contains(asset.id())',
    '(asset.recordCoordinates() || asset.clusterCoordinates())'
)
Assert-ContainsAll 'Bearer map-link current persistence scope' $mapLinkStore @(
    'Propagation.MANDATORY',
    'links.findByTokenAndActiveTrue(token)',
    'owner.equals(plan.getOwner().getId())',
    'visible(access(asset), selected, scope)'
)
Assert-ContainsAll 'Bearer map-link secure token source' $mapLinkRuntime @(
    'new SecureRandom()',
    'new byte[24]',
    'RANDOM.nextBytes(bytes)',
    'Base64.getUrlEncoder().withoutPadding()',
    'links.existsByToken(token)'
)
Assert-ContainsAll 'Bearer map-link H2 authorization regression' $mapLinkTest @(
    'creationIsAuthenticatedOwnerScopedAndAtomicWhileIncompletePrivatePlansRemainLinkShareable',
    'anonymousMapClusterAndDownloadsApplyAllExclusionsWithoutPublishingThePlan',
    'everyReadRechecksActiveLinkAndCurrentOwnershipInsteadOfTrustingAnOwnerMapCache',
    'configuredLoginPreviewKeepsNoStoreThumbnailOnlyBehaviorAndFailsClosedAfterRevocation',
    'overviewUsesStoredRouteSnapshotButLoginPreviewStillResolvesOriginalGpxAndStripsPrivateMetadata'
)
Assert-ContainsAll 'Bearer map-link independent differential regression' $mapLinkDifferential @(
    'new TravelMapLinkLegacyOracle',
    'seed < 48',
    'TravelMapLinkMapper.map(modern.map(token))',
    'TravelMapLinkMapper.cluster(modern.cluster(',
    'compareOutcome'
)
Assert-ContainsAll 'Frozen map-link workflow oracle' $mapLinkOracle @(
    'new TravelPublicMapShareResponse(',
    'new TravelMyMapOverviewResponse(',
    'new TravelLoginMapPreviewResponse(',
    'isVisibleTravelMapShareMedia',
    'findByTokenAndActiveTrue'
)
if ($mapLinkOracle.Contains('TravelMapLinksService') -or $mapLinkOracle.Contains('TravelMapLinkMapper')) {
    throw 'Frozen map-link oracle must not call the migrated map-link workflow or mapper.'
}
Assert-ContainsAll 'Map-link security regression CI selection' $ci @(
    '--tests com.playdata.calen.travel.TravelMapLinkRefactoringIntegrationTest',
    '--tests com.playdata.calen.travel.adapter.support.TravelMapLinkDifferentialTest',
    '--tests com.playdata.calen.travel.application.service.TravelMapLinksServiceTest',
    '--tests com.playdata.calen.travel.domain.policy.TravelMapLinkPolicyTest',
    '--tests com.playdata.calen.travel.adapter.out.security.TravelMapLinkRuntimeTest'
)
Assert-ContainsAll 'Authenticated atlas current scope workflow' $atlasReads @(
    'store.requireActiveViewer(viewer)',
    'publicFirst(store.publishedCompletedPlans(), store.receivedCompletedPlans(viewer))',
    'if (Boolean.TRUE.equals(photo.plan().publicShared()))',
    'tokens.issue(photo.id())',
    'var share = shares.get(photo.plan().id())',
    'if (share == null) throw new NotFoundException("Shared media not found.")',
    'window.total(), window.totalPages()',
    'cluster.representative().latitude(), cluster.representative().longitude()'
)
Assert-ContainsAll 'Atlas published-first visibility policy' $atlasPolicy @(
    'published.forEach(plan -> byId.put(',
    'received.forEach(access -> byId.putIfAbsent(',
    'published || share == null ? "PUBLIC" : "RESTRICTED"'
)
Assert-ContainsAll 'Atlas and community JPA authorization scope' $atlasStore @(
    'Propagation.MANDATORY',
    'findAllByPublicSharedTrueAndStatusOrderByPublicSharedAtDescStartDateDescIdDesc(TravelPlanStatus.COMPLETED)',
    'findAllByRecipientIdAndPlan_StatusOrderByCreatedAtDescIdDesc(viewer, TravelPlanStatus.COMPLETED)',
    'findCommunityMemoryPage(TravelRecordType.MEMORY, TravelMediaType.PHOTO',
    'TravelRecordPolicy.matches(asset.getRecord().getRecordType(), TravelRecordType.MEMORY)',
    'filter(TravelClusterPhotoMapper::hasCoordinates)'
)
Assert-ContainsAll 'Existing HMAC issuer adapter' $exhibitToken @('TravelAtlasMediaTokenPort', 'return tokens.issueToken(media)')
Assert-ContainsAll 'Atlas H2 security and state change regression' $atlasTest @(
    'publishedPlansWinOverRecipientDuplicatesAndPrivatePhotosUseTheCurrentRecipientShare',
    'privateAtlasAndItsOldClusterAndMediaAddressesStopWorkingAfterTheShareIsDeleted',
    'publicationAndCompletionChangesRevokePublicAtlasWhileCommunityMemoryRemainsADistinctSurface',
    'communitySqlTotalsRequireMemoryAndPhotoButNotCompletedPlanOrCoordinatesAndAuthorComesFromUploader',
    'allReadsAreReadOnlyMandatoryAndInactiveViewersFailBeforeChildOrTokenWork'
)
Assert-ContainsAll 'Atlas independent differential regression' $atlasDifferential @(
    'new TravelAtlasLegacyOracle',
    'seed < 48',
    'equalJson(old, TravelAtlasMapper.overview(modern.overview(99L)))',
    'TravelAtlasMapper.cluster(modern.cluster(',
    'TravelAtlasMapper.community(modern.community('
)
Assert-ContainsAll 'Frozen atlas and community workflow oracle' $atlasOracle @(
    'new TravelPublicTripsOverviewResponse(',
    'new TravelCommunityFeedPageResponse(',
    'new TravelPublicPhotoClusterSummaryResponse(',
    'new TravelPublicPhotoPinResponse(',
    'getPublicAtlasPlanAccesses',
    'buildPublicAtlasMediaUrl'
)
if ($atlasOracle.Contains('ReadTravelAtlasService') -or $atlasOracle.Contains('TravelAtlasMapper')) {
    throw 'Frozen atlas oracle must not call the migrated atlas workflow or mapper.'
}
Assert-ContainsAll 'Atlas security regression CI selection' $ci @(
    '--tests com.playdata.calen.travel.TravelAtlasRefactoringIntegrationTest',
    '--tests com.playdata.calen.travel.adapter.support.TravelAtlasDifferentialTest',
    '--tests com.playdata.calen.travel.application.service.ReadTravelAtlasServiceTest',
    '--tests com.playdata.calen.travel.domain.policy.TravelAtlasPolicyTest'
)
Write-Host 'public share authorization contract verified'
