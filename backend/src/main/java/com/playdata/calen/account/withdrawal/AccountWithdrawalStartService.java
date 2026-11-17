package com.playdata.calen.account.withdrawal;

import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.repository.AccountEmailVerificationRepository;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.EmailVerificationTokenRepository;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.social.KakaoOAuthClient;
import com.playdata.calen.account.social.KakaoOAuthProperties;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.familyalbum.service.FamilyAlbumWithdrawalService;
import com.playdata.calen.ledger.service.RecurringLedgerWithdrawalCleanup;
import com.playdata.calen.ledger.service.LedgerEntryWithdrawalCleanup;
import com.playdata.calen.ledger.service.LedgerClassificationWithdrawalCleanup;
import com.playdata.calen.ledger.repository.LedgerExcelAnalysisJobRepository;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.sharing.service.RecordShareWithdrawalService;
import com.playdata.calen.travel.service.TravelShareGroupWithdrawalService;
import com.playdata.calen.travel.service.TravelPlanTransferService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.playdata.calen.account.security.AccountRememberMeTokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal integration step only. This bean is disabled unless explicitly enabled for tests;
 * it must not be exposed until every data domain, external cleanup and backup policy is ready.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.account-withdrawal.internal-workflow-enabled", havingValue = "true")
public class AccountWithdrawalStartService {
    private final AppUserRepository users;
    private final AppUserService appUsers;
    private final SocialAccountRepository socialAccounts;
    private final EmailVerificationTokenRepository signupTokens;
    private final AccountEmailVerificationRepository profileTokens;
    private final AccountRememberMeTokens rememberMeTokens;
    private final FamilyAlbumWithdrawalService familyAlbums;
    private final TravelShareGroupWithdrawalService sharingGroups;
    private final TravelPlanTransferService planTransfer;
    private final TravelForeignMediaWithdrawalService foreignTravelMedia;
    private final RecordShareWithdrawalService recordShares;
    private final TravelSharingWithdrawalCleanup travelShares;
    private final DriveShareWithdrawalCleanup driveShares;
    private final DriveAccessLogWithdrawalCleanup driveAccessLogs;
    private final DriveProfileWithdrawalCleanup driveProfile;
    private final DriveMetadataWithdrawalCleanup driveMetadata;
    private final RecurringLedgerWithdrawalCleanup recurringCleanup;
    private final LedgerEntryWithdrawalCleanup ledgerCleanup;
    private final LedgerClassificationWithdrawalCleanup classificationCleanup;
    private final LedgerExcelAnalysisJobRepository excelJobs;
    private final LedgerAiAnalysisHistoryRepository aiHistories;
    private final AccountPreferenceWithdrawalCleanup preferenceCleanup;
    private final AccountInviteWithdrawalCleanup inviteCleanup;
    private final SupportInquiryWithdrawalCleanup supportCleanup;
    private final RecordMapWithdrawalCleanup recordMapCleanup;
    private final LedgerImageWithdrawalCleanup imageCleanup;
    private final FamilyAlbumWithdrawalCleanup familyCleanup;
    private final FamilyUploadWithdrawalInventory familyUploadInventory;
    private final PublicShareWithdrawalSuspender publicShares;
    private final TravelClusterWithdrawalCleanup travelClusters;
    private final TravelMetadataWithdrawalCleanup travelMetadata;
    private final TravelUploadWithdrawalInventory travelUploadInventory;
    private final WithdrawalAuditAnonymizer auditAnonymizer;
    private final AccountIdentityWithdrawalCleanup identityCleanup;
    private final AccountWithdrawalRequestRepository requests;
    private final WithdrawalCleanupTaskStore cleanupTasks;
    private final WithdrawalPayloadCipher cipher;
    private final WithdrawalChallengeRevoker challengeRevoker;
    private final KakaoOAuthClient kakao;
    private final KakaoOAuthProperties kakaoProperties;
    private final AdministratorWithdrawalGuard administrators;
    private final WithdrawalTransactionGuard transactionGuard;
    private final EntityManager entities;

    /** No provider or object-storage calls may be made inside this transaction. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID start(Long userId, String secondaryPin, Map<Long, Long> familyOwners, Map<Long, Long> groupOwners) {
        return start(userId, secondaryPin, familyOwners, groupOwners, Map.of());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID start(Long userId, String secondaryPin, Map<Long, Long> familyOwners, Map<Long, Long> groupOwners,
                      Map<Long, Long> travelPlanOwners) {
        if (userId == null) throw new NotFoundException("사용자 계정을 찾을 수 없습니다.");
        transactionGuard.requireReadCommitted();
        rememberMeTokens.requireBoundSchema();
        boolean administratorPrepared = administrators.lockAndCheckIfAdministrator(userId);
        var user = users.lockForAccountSecurityUpdate(userId)
                .orElseThrow(() -> new NotFoundException("사용자 계정을 찾을 수 없습니다."));
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (!user.isActive()) throw new NotFoundException("사용자 계정을 찾을 수 없습니다.");
        appUsers.ensureSecondaryPinMatches(user, secondaryPin);
        administrators.requireProtectedIfAdministrator(user, administratorPrepared);
        if (requests.existsByUserId(userId)) throw new BadRequestException("이미 처리 중인 탈퇴 요청이 있습니다.");
        if (!cipher.isConfigured()) throw new ServiceUnavailableException("탈퇴 작업을 안전하게 저장할 설정이 필요합니다.");
        var linkedKakao = socialAccounts.findAllByUserIdAndProvider(userId, SocialLoginProvider.KAKAO);
        if (!linkedKakao.isEmpty() && !kakao.isUnlinkConfigured()) {
            throw new ServiceUnavailableException("카카오 연결 해제를 위한 서버 설정이 필요합니다.");
        }
        if (linkedKakao.stream().anyMatch(account -> account.getId() == null || account.getProviderUserId() == null
                || !account.getProviderUserId().matches("[1-9][0-9]{0,18}"))) {
            throw new BadRequestException("카카오 연결 정보를 확인할 수 없습니다.");
        }
        if (!linkedKakao.isEmpty()) {
            Long appId = kakaoProperties.getExpectedAppId();
            if (appId == null || appId <= 0 || !appId.equals(kakaoProperties.getAdminAppId())
                    || linkedKakao.stream().anyMatch(account -> !appId.equals(account.getProviderAppId()))) {
                throw new ServiceUnavailableException("카카오 연결의 앱 정보를 확인한 뒤 다시 시도해 주세요.");
            }
        }
        UUID requestId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        AccountWithdrawalRequest request = new AccountWithdrawalRequest();
        request.setId(requestId.toString());
        request.setUserId(userId);
        request.setState(AccountWithdrawalRequest.State.IN_PROGRESS);
        request.setRequestedAt(now);
        request.setUpdatedAt(now);
        requests.saveAndFlush(request);

        // Both services re-check ownership, membership, active targets and name conflicts.
        familyAlbums.transferForWithdrawal(userId, familyOwners);
        sharingGroups.transferForWithdrawal(userId, groupOwners);
        planTransfer.transferForWithdrawal(userId, requestId, travelPlanOwners);
        foreignTravelMedia.preserveForPlanOwners(userId, requestId);
        user.setActive(false);
        challengeRevoker.revoke(userId, now);
        signupTokens.invalidateOpenTokens(userId, now);
        profileTokens.invalidateOpenTokens(userId, now);
        rememberMeTokens.removeUserTokensById(userId);
        familyCleanup.scheduleAndRemove(userId, requestId);
        familyUploadInventory.captureAndRemove(userId, requestId);
        recordShares.removeEnvelopesForInactiveUser(userId);
        inviteCleanup.anonymize(userId, requestId);
        travelShares.removeEdges(userId, requestId);
        driveShares.removeDirectShares(userId, requestId);
        recurringCleanup.removeRulesAndOccurrences(userId);
        ledgerCleanup.removeEntriesAndHistories(userId);
        classificationCleanup.removeRules(userId);
        classificationCleanup.removeCatalog(userId);
        excelJobs.removeForWithdrawal(userId);
        aiHistories.removeForWithdrawal(userId);
        preferenceCleanup.removePreferencesAndGoals(userId);
        driveProfile.captureAndRemoveSettings(userId, requestId);
        supportCleanup.scheduleAndRemove(userId, requestId);
        publicShares.suspend(userId, now);
        driveAccessLogs.anonymize(userId, requestId);
        travelMetadata.requireNoForeignReferences(userId, requestId);
        travelClusters.removeDerivedData(userId, requestId);
        recordMapCleanup.scheduleAndRemove(userId, requestId);
        imageCleanup.scheduleAndRemove(userId, requestId);
        travelUploadInventory.captureAndRemove(userId, requestId);
        travelMetadata.captureAndRemove(userId, requestId);
        driveMetadata.captureAndRemove(userId, requestId);
        auditAnonymizer.anonymize(userId);
        for (var account : linkedKakao) {
            cleanupTasks.enqueue(requestId, WithdrawalCleanupKind.KAKAO_UNLINK,
                    "social:" + account.getId(), account.getProviderUserId());
        }
        identityCleanup.removeCredentialsAndProofs(userId, requestId);
        return requestId;
    }
}
