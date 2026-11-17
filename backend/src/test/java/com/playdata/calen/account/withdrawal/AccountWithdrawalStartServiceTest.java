package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.playdata.calen.account.domain.AccountEmailVerification;
import com.playdata.calen.account.domain.AccountInvite;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.domain.EmailVerificationToken;
import com.playdata.calen.account.domain.HouseholdGoal;
import com.playdata.calen.account.domain.SocialAccount;
import com.playdata.calen.account.domain.SocialLoginProvider;
import com.playdata.calen.account.domain.SupportInquiry;
import com.playdata.calen.account.domain.UserLayoutSetting;
import com.playdata.calen.account.repository.AccountEmailVerificationRepository;
import com.playdata.calen.account.repository.AccountInviteRepository;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.repository.EmailVerificationTokenRepository;
import com.playdata.calen.account.repository.HouseholdGoalRepository;
import com.playdata.calen.account.repository.SocialAccountRepository;
import com.playdata.calen.account.repository.SupportInquiryRepository;
import com.playdata.calen.account.repository.UserLayoutSettingRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.AccountInviteService;
import com.playdata.calen.account.service.AdminService;
import com.playdata.calen.account.social.KakaoOAuthClient;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.familyalbum.domain.FamilyCategory;
import com.playdata.calen.familyalbum.domain.FamilyCategoryMember;
import com.playdata.calen.familyalbum.domain.FamilyMediaAsset;
import com.playdata.calen.familyalbum.domain.FamilyMediaUploadGrant;
import com.playdata.calen.familyalbum.dto.FamilyAlbumCreateRequest;
import com.playdata.calen.familyalbum.repository.FamilyAlbumRepository;
import com.playdata.calen.familyalbum.repository.FamilyCategoryMemberRepository;
import com.playdata.calen.familyalbum.repository.FamilyCategoryRepository;
import com.playdata.calen.familyalbum.repository.FamilyMediaAssetRepository;
import com.playdata.calen.familyalbum.repository.FamilyMediaUploadGrantRepository;
import com.playdata.calen.familyalbum.service.FamilyAlbumService;
import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.LedgerEntryChangeAction;
import com.playdata.calen.ledger.domain.LedgerEntryChangeHistory;
import com.playdata.calen.ledger.domain.LedgerClassificationRule;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisHistory;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisMode;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisStatus;
import com.playdata.calen.ledger.domain.LedgerImageAnalysisRequest;
import com.playdata.calen.ledger.domain.PaymentMethod;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import com.playdata.calen.ledger.domain.RecurringLedgerMode;
import com.playdata.calen.ledger.domain.RecurringLedgerOccurrence;
import com.playdata.calen.ledger.domain.RecurringLedgerOccurrenceStatus;
import com.playdata.calen.ledger.domain.RecurringLedgerRule;
import com.playdata.calen.ledger.repository.CategoryGroupRepository;
import com.playdata.calen.ledger.repository.LedgerEntryChangeHistoryRepository;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.LedgerClassificationRuleRepository;
import com.playdata.calen.ledger.repository.LedgerExcelAnalysisJobRepository;
import com.playdata.calen.ledger.repository.LedgerAiAnalysisHistoryRepository;
import com.playdata.calen.ledger.repository.LedgerImageAnalysisRequestRepository;
import com.playdata.calen.ledger.repository.PaymentMethodRepository;
import com.playdata.calen.ledger.repository.RecurringLedgerOccurrenceRepository;
import com.playdata.calen.ledger.repository.RecurringLedgerRuleRepository;
import com.playdata.calen.ledger.service.LedgerClassificationWithdrawalCleanup;
import com.playdata.calen.ledger.service.LedgerEntryWithdrawalCleanup;
import com.playdata.calen.ledger.service.RecurringLedgerWithdrawalCleanup;
import com.playdata.calen.ledger.service.LedgerAiExcelJobWriter;
import com.playdata.calen.ledger.ai.LedgerAiAnalysisHistoryWriter;
import com.playdata.calen.ledger.ocr.LedgerImageAnalysisHistoryWriter;
import com.playdata.calen.ledger.adapter.out.persistence.JpaLedgerImageInputReferenceAdapter;
import com.playdata.calen.ledger.domain.model.LedgerImageStorageData.StoredImage;
import com.playdata.calen.recordmap.domain.PlaceCollection;
import com.playdata.calen.recordmap.domain.PlacePhoto;
import com.playdata.calen.recordmap.domain.PlaceVisit;
import com.playdata.calen.recordmap.domain.RecordMapShare;
import com.playdata.calen.recordmap.domain.RecordPlace;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveItemType;
import com.playdata.calen.drive.domain.DriveItemVersion;
import com.playdata.calen.drive.domain.DriveUploadGrant;
import com.playdata.calen.drive.domain.DriveDownloadLinkAccessLog;
import com.playdata.calen.drive.domain.DriveDownloadLink;
import com.playdata.calen.drive.domain.DriveShare;
import com.playdata.calen.drive.domain.DriveProfileSettings;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.drive.repository.DriveItemVersionRepository;
import com.playdata.calen.drive.repository.DriveUploadGrantRepository;
import com.playdata.calen.drive.repository.DriveProfileSettingsRepository;
import com.playdata.calen.drive.repository.DriveDownloadLinkAccessLogRepository;
import com.playdata.calen.drive.repository.DriveDownloadLinkRepository;
import com.playdata.calen.drive.repository.DriveShareRepository;
import com.playdata.calen.drive.service.DriveDownloadLinkAccessLogService;
import com.playdata.calen.drive.service.DriveProfileService;
import com.playdata.calen.sharing.domain.RecordShare;
import com.playdata.calen.sharing.domain.RecordShareKind;
import com.playdata.calen.sharing.domain.RecordShareStatus;
import com.playdata.calen.sharing.repository.RecordShareRepository;
import com.playdata.calen.sharing.service.RecordShareWithdrawalService;
import com.playdata.calen.sharing.service.RecordSharingService;
import com.playdata.calen.travel.domain.TravelShareGroup;
import com.playdata.calen.travel.domain.TravelShareGroupMember;
import com.playdata.calen.travel.domain.TravelPhotoCluster;
import com.playdata.calen.travel.domain.TravelPhotoClusterMember;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelPlanShare;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.domain.TravelRouteSegment;
import com.playdata.calen.travel.domain.TravelUploadGrant;
import com.playdata.calen.travel.application.port.in.RefreshTravelPhotoClustersUseCase;
import com.playdata.calen.travel.application.port.in.ManageTravelSharingUseCase;
import com.playdata.calen.travel.application.port.in.ReadTravelExhibitsUseCase;
import com.playdata.calen.travel.application.port.in.WriteTravelBudgetUseCase;
import com.playdata.calen.travel.application.port.in.TravelMapLinksUseCase;
import com.playdata.calen.travel.domain.model.TravelBudgetData.Draft;
import com.playdata.calen.travel.domain.model.TravelMapLinkData;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.PhotoPoint;
import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;
import com.playdata.calen.travel.service.TravelClusterRefreshCoordinator;
import com.playdata.calen.travel.service.TravelClusterWithdrawalFence;
import com.playdata.calen.travel.service.TravelLoginMapPreviewService;
import com.playdata.calen.travel.service.TravelDriveLinkService;
import com.playdata.calen.travel.service.TravelPlanWithdrawalService;
import com.playdata.calen.travel.service.TravelPlanTransferService;
import com.playdata.calen.travel.service.TravelShareGroupWithdrawalService;
import com.playdata.calen.travel.dto.AdminLoginMapPreviewUpdateRequest;
import com.playdata.calen.travel.repository.TravelShareGroupMemberRepository;
import com.playdata.calen.travel.repository.TravelShareGroupRepository;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import com.playdata.calen.travel.repository.TravelPlanShareRepository;
import com.playdata.calen.travel.repository.TravelBudgetItemRepository;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import com.playdata.calen.travel.repository.TravelRouteSegmentRepository;
import com.playdata.calen.travel.repository.TravelMapShareLinkRepository;
import com.playdata.calen.travel.repository.TravelUploadGrantRepository;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(properties = {
        "app.seed.enabled=false", "app.account-withdrawal.internal-workflow-enabled=true",
        "app.security.withdrawal-task-seal-key=test-withdrawal-secret-at-least-32-characters",
        "app.auth.kakao.expected-app-id=1234", "app.auth.kakao.admin-app-id=1234",
        "spring.datasource.url=jdbc:h2:mem:withdrawal-start-${random.uuid};MODE=MySQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class AccountWithdrawalStartServiceTest {
    @Autowired AccountWithdrawalStartService start;
    @Autowired AccountPreferenceWithdrawalCleanup preferenceCleanup;
    @Autowired LedgerClassificationWithdrawalCleanup classificationCleanup;
    @Autowired LedgerAiExcelJobWriter excelWriter;
    @Autowired LedgerExcelAnalysisJobRepository excelJobs;
    @Autowired LedgerAiAnalysisHistoryWriter aiWriter;
    @Autowired LedgerAiAnalysisHistoryRepository aiHistories;
    @Autowired LedgerImageAnalysisHistoryWriter imageWriter;
    @Autowired LedgerImageAnalysisRequestRepository imageHistories;
    @Autowired JpaLedgerImageInputReferenceAdapter imageReferences;
    @Autowired AccountWithdrawalRequestRepository requests;
    @Autowired WithdrawalCleanupTaskRepository tasks;
    @Autowired KakaoWithdrawalPreflight kakaoPreflight;
    @MockitoSpyBean WithdrawalPayloadCipher cipher;
    @Autowired AppUserService appUsers;
    @Autowired AccountInviteService inviteService;
    @Autowired AccountInviteRepository invites;
    @Autowired AdminService admins;
    @Autowired AppUserRepository users;
    @Autowired SocialAccountRepository social;
    @Autowired SupportInquiryRepository inquiries;
    @Autowired EmailVerificationTokenRepository signupTokens;
    @Autowired AccountEmailVerificationRepository profileTokens;
    @Autowired HouseholdGoalRepository goals;
    @Autowired UserLayoutSettingRepository layouts;
    @Autowired FamilyCategoryRepository familyCategories;
    @Autowired FamilyCategoryMemberRepository familyMembers;
    @Autowired FamilyMediaAssetRepository familyMedia;
    @Autowired FamilyMediaUploadGrantRepository familyUploadGrants;
    @Autowired FamilyUploadWithdrawalInventory familyUploadInventory;
    @Autowired FamilyAlbumRepository familyAlbums;
    @Autowired FamilyAlbumService familyService;
    @Autowired TravelShareGroupRepository groups;
    @Autowired TravelShareGroupMemberRepository groupMembers;
    @Autowired TravelPlanRepository travelPlans;
    @Autowired TravelPlanWithdrawalService travelPlanWithdrawal;
    @Autowired TravelPlanTransferService travelPlanTransfer;
    @Autowired TravelShareGroupWithdrawalService groupTransfer;
    @Autowired TravelPlanShareRepository planShares;
    @Autowired TravelBudgetItemRepository travelBudgetItems;
    @Autowired WriteTravelBudgetUseCase travelBudgetWrites;
    @Autowired TravelExpenseRecordRepository travelRecords;
    @Autowired TravelMediaAssetRepository travelMedia;
    @Autowired TravelRouteSegmentRepository travelRoutes;
    @Autowired TravelUploadGrantRepository travelUploadGrants;
    @Autowired TravelMapShareLinkRepository travelMapLinks;
    @Autowired TravelMapLinksUseCase mapLinks;
    @Autowired TravelLoginMapPreviewService loginPreview;
    @Autowired TravelDriveLinkService travelDriveLinks;
    @Autowired ManageTravelSharingUseCase travelSharing;
    @Autowired ReadTravelExhibitsUseCase exhibits;
    @Autowired RecordShareRepository shares;
    @Autowired DriveItemRepository driveItems;
    @Autowired DriveItemVersionRepository driveVersions;
    @Autowired DriveUploadGrantRepository driveUploadGrants;
    @Autowired DriveWithdrawalInventory driveInventory;
    @Autowired DriveMetadataWithdrawalCleanup driveMetadataCleanup;
    @Autowired DriveDownloadLinkRepository driveLinks;
    @Autowired DriveProfileSettingsRepository driveProfileSettings;
    @Autowired DriveProfileService driveProfileService;
    @Autowired DriveProfileWithdrawalCleanup driveProfileCleanup;
    @Autowired DriveShareRepository driveShares;
    @Autowired DriveDownloadLinkAccessLogRepository driveAccessLogRows;
    @Autowired DriveAccessLogWithdrawalCleanup driveAccessCleanup;
    @Autowired DriveShareWithdrawalCleanup driveShareCleanup;
    @Autowired TravelClusterWithdrawalCleanup travelClusterCleanup;
    @Autowired TravelSharingWithdrawalCleanup travelSharingCleanup;
    @Autowired FamilyAlbumWithdrawalCleanup familyCleanup;
    @Autowired LedgerImageWithdrawalCleanup imageCleanup;
    @Autowired RecordMapWithdrawalCleanup recordMapCleanup;
    @Autowired SupportInquiryWithdrawalCleanup supportCleanup;
    @Autowired PublicShareWithdrawalSuspender publicShareCleanup;
    @Autowired WithdrawalAuditAnonymizer auditCleanup;
    @Autowired LedgerEntryWithdrawalCleanup ledgerCleanup;
    @Autowired RecurringLedgerWithdrawalCleanup recurringCleanup;
    @Autowired RecordShareWithdrawalService recordShareCleanup;
    @Autowired RecordSharingService recordSharing;
    @Autowired TravelWithdrawalInventory travelInventory;
    @MockitoSpyBean RefreshTravelPhotoClustersUseCase clusterProjection;
    @MockitoSpyBean TravelClusterWithdrawalFence clusterFence;
    @Autowired TravelClusterRefreshCoordinator clusterCoordinator;
    @Autowired CategoryGroupRepository ledgerCategories;
    @Autowired PaymentMethodRepository payments;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired LedgerEntryChangeHistoryRepository ledgerHistories;
    @Autowired LedgerClassificationRuleRepository classificationRules;
    @Autowired RecurringLedgerRuleRepository rules;
    @Autowired RecurringLedgerOccurrenceRepository occurrences;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entities;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired ObjectMapper json;
    @MockitoBean KakaoOAuthClient kakao;

    @Test void withdrawalRejectsAChangedPinEvenWhenTheRequestHasACachedUser() throws Exception {
        AppUser owner = user();
        var requestEntityManager = entityManagerFactory.createEntityManager();
        var pool = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntityManager));
        try {
            AppUser stale = requestEntityManager.find(AppUser.class, owner.getId());
            String oldPinHash = stale.getSecondaryPinHash();
            pool.submit(() -> appUsers.updateSecondaryPin(owner.getId(), "12345678", "87654321"))
                    .get(5, TimeUnit.SECONDS);
            assertThat(stale.getSecondaryPinHash()).isEqualTo(oldPinHash);
            assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                    .isInstanceOf(BadCredentialsException.class);
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestEntityManager.close();
            pool.shutdownNow();
        }
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
    }

    @BeforeEach void createEmbeddingOutboxSchema() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V20261006_032__ledger_embedding_sync_jobs.sql"))
                .execute(dataSource);
    }

    @Test void preferencesCannotBeRemovedForAnActiveOrMerelyDisabledAccount() {
        AppUser active = user(), disabled = user();
        long[] activePreferences = preferences(active), disabledPreferences = preferences(disabled);
        AppUser disabledWithPreferences = users.findById(disabled.getId()).orElseThrow();
        disabledWithPreferences.setActive(false);
        users.saveAndFlush(disabledWithPreferences);
        for (Long id : List.of(active.getId(), disabled.getId())) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> preferenceCleanup.removePreferencesAndGoals(id)))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("탈퇴 처리 중");
        }
        assertThat(layouts.findById(activePreferences[0])).isPresent();
        assertThat(goals.findById(activePreferences[1])).isPresent();
        assertThat(layouts.findById(disabledPreferences[0])).isPresent();
        assertThat(goals.findById(disabledPreferences[1])).isPresent();
        assertThat(users.findById(active.getId()).orElseThrow().getHouseholdAggregateSettingsJson()).isEqualTo("[1]");
        assertThat(users.findById(disabled.getId()).orElseThrow().getHouseholdAggregateSettingsJson()).isEqualTo("[1]");
    }

    @Test void creatorWithdrawalRevokesPendingInvitesAndPreservesAcceptedAccounts() throws Exception {
        AppUser creator = user(), other = user();
        String usedToken = inviteService.createInvite(creator.getId(), 72).token();
        AccountInvite usedBefore = invites.findByTokenHash(inviteHash(usedToken)).orElseThrow();
        AppUser invited = inviteService.acceptInvite(usedToken, unique(), "초대 회원", "test-password", "12345678");
        String pendingToken = inviteService.createInvite(creator.getId(), 72).token();
        AccountInvite pendingBefore = invites.findByTokenHash(inviteHash(pendingToken)).orElseThrow();
        String otherToken = inviteService.createInvite(other.getId(), 72).token();

        start.start(creator.getId(), "12345678", Map.of(), Map.of());

        AccountInvite used = invites.findById(usedBefore.getId()).orElseThrow();
        AccountInvite pending = invites.findById(pendingBefore.getId()).orElseThrow();
        assertThat(used.getCreatedBy()).isNull();
        assertThat(used.getUsedAt()).isNotNull();
        assertThat(used.getTokenHash()).isNotEqualTo(inviteHash(usedToken));
        assertThat(jdbc.queryForObject("select used_by_user_id from account_invites where id=?", Long.class, used.getId()))
                .isEqualTo(invited.getId());
        assertThat(pending.getCreatedBy()).isNull();
        assertThat(pending.getRevokedAt()).isNotNull();
        assertThat(pending.getTokenHash()).isNotEqualTo(inviteHash(pendingToken));
        assertThat(users.findById(invited.getId()).orElseThrow().isActive()).isTrue();
        assertThatThrownBy(() -> inviteService.getInviteDetails(pendingToken)).isInstanceOf(BadRequestException.class);
        assertThat(inviteService.getInviteDetails(otherToken).expiresAt()).isNotNull();
        assertThat(admins.getDashboard().recentInvites()).filteredOn(summary -> summary.id().equals(pending.getId()))
                .singleElement().satisfies(summary -> {
                    assertThat(summary.status()).isEqualTo("REVOKED");
                    assertThat(summary.createdByLoginId()).isNull();
                });
    }

    @Test void acceptedUserWithdrawalDetachesIdentityWithoutChangingTheCreatorOrUsedStatus() throws Exception {
        AppUser creator = user();
        String token = inviteService.createInvite(creator.getId(), 72).token();
        AccountInvite before = invites.findByTokenHash(inviteHash(token)).orElseThrow();
        AppUser invited = inviteService.acceptInvite(token, unique(), "초대 회원", "test-password", "12345678");

        start.start(invited.getId(), "12345678", Map.of(), Map.of());

        AccountInvite after = invites.findById(before.getId()).orElseThrow();
        assertThat(after.getUsedAt()).isNotNull();
        assertThat(after.getUsedBy()).isNull();
        assertThat(after.getTokenHash()).isNotEqualTo(inviteHash(token));
        assertThat(jdbc.queryForObject("select created_by_user_id from account_invites where id=?", Long.class, before.getId()))
                .isEqualTo(creator.getId());
        assertThat(users.findById(creator.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test void inviteRequestsWaitingForCreatorWithdrawalCannotCreateAnAccount() throws Exception {
        AppUser creator = user(); String token = inviteService.createInvite(creator.getId(), 72).token();
        String newLogin = unique();
        kakao(creator, "1556677889"); when(kakao.isUnlinkConfigured()).thenReturn(true);
        var anonymized = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            anonymized.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("withdrawal timed out");
            return invocation.callRealMethod();
        }).when(cipher).encrypt(any(), any(), eq(WithdrawalCleanupKind.KAKAO_UNLINK.name()), anyString());
        var pool = Executors.newFixedThreadPool(3);
        try {
            var withdrawal = pool.submit(() -> start.start(creator.getId(), "12345678", Map.of(), Map.of()));
            assertThat(anonymized.await(5, TimeUnit.SECONDS)).isTrue();
            var details = pool.submit(() -> inviteService.getInviteDetails(token));
            var acceptance = pool.submit(() -> inviteService.acceptInvite(token, newLogin, "신규 회원", "test-password", "12345678"));
            assertThatThrownBy(() -> details.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThatThrownBy(() -> acceptance.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); withdrawal.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> details.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BadRequestException.class);
            assertThatThrownBy(() -> acceptance.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BadRequestException.class);
            assertThat(users.findByLoginId(newLogin)).isEmpty();
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void classificationRulesCannotBeRemovedForAnActiveOrMerelyDisabledAccount() {
        AppUser active = user(), disabled = user();
        long activeRule = classificationRule(active), disabledRule = classificationRule(disabled);
        AppUser disabledUser = users.findById(disabled.getId()).orElseThrow();
        disabledUser.setActive(false);
        users.saveAndFlush(disabledUser);
        for (Long id : List.of(active.getId(), disabled.getId())) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> classificationCleanup.removeRules(id)))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("탈퇴 처리 중");
            assertThatThrownBy(() -> new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> classificationCleanup.removeCatalog(id)))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("탈퇴 처리 중");
        }
        assertThat(classificationRules.findById(activeRule)).isPresent();
        assertThat(classificationRules.findById(disabledRule)).isPresent();
    }

    @Test void startCommitsTransfersSessionRevocationAndEncryptedKakaoTaskTogether() throws Exception {
        AppUser owner = user(), target = user(), other = user();
        owner.setEmail(unique() + "@example.test");
        users.saveAndFlush(owner);
        String reservedLogin = owner.getLoginId(), reservedEmail = owner.getEmail();
        long[] ownerPreferences = preferences(owner), targetPreferences = preferences(target);
        long[] ownerRecurring = recurring(owner), targetRecurring = recurring(target);
        long[] ownerLedger = ledger(owner), targetLedger = ledger(target);
        long ownerClassification = classificationRule(owner), targetClassification = classificationRule(target);
        FamilyCategory family = family(owner, target);
        FamilyMediaAsset ownerPhoto = familyPhoto(family, owner), targetPhoto = familyPhoto(family, target);
        String ownerPhotoPath = ownerPhoto.getStoragePath();
        TravelShareGroup group = group(owner, target, other);
        RecordShare departingShare = share(group, owner, target, 1L);
        RecordShare otherShare = share(group, target, other, 2L);
        SocialAccount linked = kakao(owner, "123456789");
        linked.setEmail("owner-kakao@example.test");
        social.saveAndFlush(linked);
        EmailVerificationToken signup = signupToken(owner);
        AccountEmailVerification profile = profileToken(owner);
        jdbc.update("insert into persistent_logins(username, series, token, last_used) values (?, ?, ?, current_timestamp)",
                owner.getLoginId(), "owner-" + UUID.randomUUID(), "token");
        jdbc.update("insert into persistent_logins(username, series, token, last_used) values (?, ?, ?, current_timestamp)",
                target.getLoginId(), "other-" + UUID.randomUUID(), "token");
        when(kakao.isUnlinkConfigured()).thenReturn(true);

        UUID requestId = start.start(owner.getId(), "12345678", Map.of(family.getId(), target.getId()),
                Map.of(group.getId(), target.getId()));

        AppUser pendingOwner = users.findById(owner.getId()).orElseThrow();
        assertThat(pendingOwner.isActive()).isFalse();
        assertThat(pendingOwner.getDisplayName()).isEqualTo("탈퇴 처리 중");
        assertThat(pendingOwner.getPasswordHash()).isNull();
        assertThat(pendingOwner.getSecondaryPinHash()).isNull();
        assertThat(pendingOwner.getLoginId()).isEqualTo(reservedLogin);
        assertThat(pendingOwner.getEmail()).isEqualTo(reservedEmail);
        assertThat(social.findById(linked.getId()).orElseThrow().getEmail()).isNull();
        assertThat(pendingOwner.getHouseholdAggregateSettingsJson()).isNull();
        assertThat(users.findById(target.getId()).orElseThrow().getHouseholdAggregateSettingsJson()).isEqualTo("[1]");
        assertThat(layouts.findById(ownerPreferences[0])).isEmpty();
        assertThat(goals.findById(ownerPreferences[1])).isEmpty();
        assertThat(layouts.findById(targetPreferences[0])).isPresent();
        assertThat(goals.findById(targetPreferences[1])).isPresent();
        assertThat(requests.findByUserId(owner.getId()).orElseThrow().getState())
                .isEqualTo(AccountWithdrawalRequest.State.IN_PROGRESS);
        assertThat(familyCategories.findById(family.getId()).orElseThrow().getOwner().getId()).isEqualTo(target.getId());
        assertThat(familyMedia.findById(ownerPhoto.getId()).orElseThrow().getOwner().getId()).isEqualTo(target.getId());
        assertThat(familyMedia.findById(ownerPhoto.getId()).orElseThrow().getStoragePath()).isEqualTo(ownerPhotoPath);
        assertThat(familyMedia.findById(targetPhoto.getId()).orElseThrow().getOwner().getId()).isEqualTo(target.getId());
        assertThat(groups.findById(group.getId()).orElseThrow().getOwner().getId()).isEqualTo(target.getId());
        assertThat(shares.findById(departingShare.getId())).isEmpty();
        assertThat(shares.findById(otherShare.getId())).isPresent();
        assertThat(rules.findById(ownerRecurring[0])).isEmpty();
        assertThat(occurrences.findById(ownerRecurring[1])).isEmpty();
        assertThat(rules.findById(targetRecurring[0])).isPresent();
        assertThat(occurrences.findById(targetRecurring[1])).isPresent();
        assertThat(ledgerEntries.findById(ownerLedger[0])).isEmpty();
        assertThat(ledgerHistories.findById(ownerLedger[1])).isEmpty();
        assertThat(ledgerEntries.findById(targetLedger[0])).isPresent();
        assertThat(ledgerHistories.findById(targetLedger[1])).isPresent();
        assertThat(classificationRules.findById(ownerClassification)).isEmpty();
        assertThat(classificationRules.findById(targetClassification)).isPresent();
        assertThat(ledgerCategories.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(owner.getId())).isEmpty();
        assertThat(payments.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(owner.getId())).isEmpty();
        assertThat(ledgerCategories.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(target.getId())).isNotEmpty();
        assertThat(payments.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(target.getId())).isNotEmpty();
        assertThat(jdbc.queryForObject("select count(*) from ledger_embedding_sync_jobs where owner_id = ? and source_id = ? and record_type = 'recurring_rule'", Integer.class, owner.getId(), ownerRecurring[0])).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ledger_embedding_sync_jobs where owner_id = ? and source_id = ? and record_type = 'ledger_entry'", Integer.class, owner.getId(), ownerLedger[0])).isEqualTo(1);
        assertThat(signupTokens.findById(signup.getId())).isEmpty();
        assertThat(profileTokens.findById(profile.getId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from persistent_logins where username = ?", Integer.class, owner.getLoginId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from persistent_logins where username = ?", Integer.class, target.getLoginId())).isEqualTo(1);
        var queued = tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())).toList();
        assertThat(queued).singleElement().satisfies(task -> {
            assertThat(task.getKind()).isEqualTo(WithdrawalCleanupKind.KAKAO_UNLINK);
            assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
            assertThat(task.getEncryptedPayload()).doesNotContain("123456789");
            assertThat(cipher.decrypt(requestId, UUID.fromString(task.getId()), task.getKind().name(), task.getEncryptedPayload()))
                    .isEqualTo(linked.getProviderUserId());
        });
    }

    @Test void identityCleanupRollsBackWithAnUncommittedWithdrawal() throws Exception {
        AppUser owner = user();
        owner.setEmail(unique() + "@example.test");
        users.saveAndFlush(owner);
        String displayName = owner.getDisplayName(), passwordHash = owner.getPasswordHash(), pinHash = owner.getSecondaryPinHash();
        EmailVerificationToken signup = signupToken(owner);
        AccountEmailVerification profile = profileToken(owner);
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);

        transaction.executeWithoutResult(status -> {
            start.start(owner.getId(), "12345678", Map.of(), Map.of());
            status.setRollbackOnly();
        });

        AppUser restored = users.findById(owner.getId()).orElseThrow();
        assertThat(restored.isActive()).isTrue();
        assertThat(restored.getDisplayName()).isEqualTo(displayName);
        assertThat(restored.getPasswordHash()).isEqualTo(passwordHash);
        assertThat(restored.getSecondaryPinHash()).isEqualTo(pinHash);
        assertThat(signupTokens.findById(signup.getId())).isPresent();
        assertThat(profileTokens.findById(profile.getId())).isPresent();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
    }

    @Test void invalidGroupChoiceRollsBackEarlierFamilyTransferAndAccountDeactivation() {
        AppUser owner = user(), target = user();
        FamilyCategory family = family(owner, target);
        FamilyMediaAsset ownerPhoto = familyPhoto(family, owner);
        TravelShareGroup group = group(owner, target);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(family.getId(), target.getId()), Map.of()))
                .isInstanceOf(BadRequestException.class);
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(familyCategories.findById(family.getId()).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
        assertThat(familyMedia.findById(ownerPhoto.getId()).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
        assertThat(groups.findById(group.getId()).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
    }

    @Test void taskPersistenceFailureRollsBackTransfersDeactivationAndTokenRevocation() throws Exception {
        AppUser owner = user(), target = user();
        DriveShareFixture directShare = directShare(target, owner, null);
        ClusterFixture projected = cluster(owner);
        TravelPlan sourcePlan = travelPlan(owner);
        TravelPlan privatePlan = travelPlan(owner);
        TravelExpenseRecord privateRecord = travelRecord(privatePlan);
        String privateFile = UUID.randomUUID() + "-rollback.jpg";
        TravelMediaAsset privatePhoto = travelAsset(privatePlan, privateRecord, owner, privateFile,
                "travel-media/" + owner.getId() + "/" + privatePlan.getId() + "/" + privateRecord.getId() + "/" + privateFile);
        String publicMap = mapLinks.create(owner.getId(), mapLinkDraft(sourcePlan)).token();
        long exhibitShare = travelSharing.shareCompletedPlan(owner.getId(), sourcePlan.getId(), target.getLoginId()).id();
        long[] ownerPreferences = preferences(owner);
        long[] ownerRecurring = recurring(owner);
        long[] ownerLedger = ledger(owner);
        long ownerClassification = classificationRule(owner);
        FamilyCategory family = family(owner, target);
        TravelShareGroup group = group(owner, target);
        RecordShare shared = share(group, owner, target, 7L);
        kakao(owner, "323456789");
        EmailVerificationToken signup = signupToken(owner);
        AccountEmailVerification profile = profileToken(owner);
        jdbc.update("insert into persistent_logins(username, series, token, last_used) values (?, ?, ?, current_timestamp)",
                owner.getLoginId(), "rollback-" + UUID.randomUUID(), "token");
        when(kakao.isUnlinkConfigured()).thenReturn(true);
        doThrow(new ServiceUnavailableException("test task-store failure")).when(cipher)
                .encrypt(any(), any(), eq(WithdrawalCleanupKind.KAKAO_UNLINK.name()), anyString());
        long previousTaskCount = tasks.count();

        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(family.getId(), target.getId()),
                Map.of(group.getId(), target.getId()), Map.of(sourcePlan.getId(), target.getId())))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(users.findById(owner.getId()).orElseThrow().getHouseholdAggregateSettingsJson()).isEqualTo("[1]");
        assertThat(layouts.findById(ownerPreferences[0])).isPresent();
        assertThat(goals.findById(ownerPreferences[1])).isPresent();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(familyCategories.findById(family.getId()).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
        assertThat(groups.findById(group.getId()).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
        assertThat(shares.findById(shared.getId())).isPresent();
        assertThat(driveShares.findById(directShare.departingShare())).isPresent();
        assertThat(planShares.findById(exhibitShare)).isPresent();
        assertThat(travelMapLinks.findByTokenAndActiveTrue(publicMap)).isPresent();
        assertThat(travelPlans.findById(privatePlan.getId())).isPresent();
        assertThat(travelRecords.findById(privateRecord.getId())).isPresent();
        assertThat(travelMedia.findById(privatePhoto.getId())).isPresent();
        assertThat(tasks.count()).isEqualTo(previousTaskCount);
        assertThat(driveItems.findById(directShare.item()).orElseThrow().isSharedFile()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from travel_photo_clusters where id = ?", Integer.class,
                projected.clusterId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from travel_photo_cluster_refresh_jobs where owner_id = ?", Integer.class,
                owner.getId())).isEqualTo(1);
        assertThat(rules.findById(ownerRecurring[0])).isPresent();
        assertThat(occurrences.findById(ownerRecurring[1])).isPresent();
        assertThat(ledgerEntries.findById(ownerLedger[0])).isPresent();
        assertThat(ledgerHistories.findById(ownerLedger[1])).isPresent();
        assertThat(classificationRules.findById(ownerClassification)).isPresent();
        assertThat(ledgerCategories.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(owner.getId())).isNotEmpty();
        assertThat(payments.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(owner.getId())).isNotEmpty();
        assertThat(jdbc.queryForObject("select count(*) from ledger_embedding_sync_jobs where owner_id = ?", Integer.class, owner.getId())).isZero();
        assertThat(signupTokens.findById(signup.getId()).orElseThrow().getUsedAt()).isNull();
        assertThat(profileTokens.findById(profile.getId()).orElseThrow().getUsedAt()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from persistent_logins where username = ?", Integer.class, owner.getLoginId())).isEqualTo(1);
    }

    @Test void withdrawalTransfersSharedPlanAndRemovesOnlyDepartingMemberships() {
        AppUser departing = user(), owner = user(), other = user();
        TravelPlan ownPlan = travelPlan(departing), anotherPlan = travelPlan(owner);
        long outgoing = travelSharing.shareCompletedPlan(departing.getId(), ownPlan.getId(), other.getLoginId()).id();
        long incoming = travelSharing.shareCompletedPlan(owner.getId(), anotherPlan.getId(), departing.getLoginId()).id();
        long retained = travelSharing.shareCompletedPlan(owner.getId(), anotherPlan.getId(), other.getLoginId()).id();
        TravelShareGroup otherGroup = group(owner, departing, other);
        TravelShareGroup emptyOwnGroup = group(departing);
        String removedLink = mapLinks.create(departing.getId(), mapLinkDraft(ownPlan)).token();
        String retainedLink = mapLinks.create(owner.getId(), mapLinkDraft(anotherPlan)).token();

        start.start(departing.getId(), "12345678", Map.of(), Map.of(), Map.of(ownPlan.getId(), other.getId()));

        assertThat(planShares.findById(outgoing)).isEmpty();
        assertThat(planShares.findById(incoming)).isEmpty();
        assertThat(planShares.findById(retained)).isPresent();
        assertThatThrownBy(() -> exhibits.receivedDetail(other.getId(), outgoing)).isInstanceOf(NotFoundException.class);
        assertThat(exhibits.receivedDetail(other.getId(), retained).id()).isEqualTo(retained);
        assertThat(travelPlans.findById(ownPlan.getId())).isPresent();
        assertThat(travelPlans.findById(ownPlan.getId()).orElseThrow().getOwner().getId()).isEqualTo(other.getId());
        assertThat(travelPlans.findById(anotherPlan.getId())).isPresent();
        assertThat(groups.findById(otherGroup.getId())).isPresent();
        assertThat(groupMembers.findAllByGroupIdOrderByIdAsc(otherGroup.getId()))
                .extracting(member -> member.getMember().getId()).containsExactly(other.getId());
        assertThat(groups.findById(emptyOwnGroup.getId())).isEmpty();
        assertThat(travelMapLinks.findByTokenAndActiveTrue(removedLink)).isEmpty();
        assertThat(travelMapLinks.findByTokenAndActiveTrue(retainedLink)).isPresent();
        assertThat(mapLinks.preview(retainedLink)).isNotNull();
    }

    @Test void publicMapRequestsWaitingForWithdrawalCannotReadTheDeletedLink() throws Exception {
        AppUser owner = user(), successor = user(); TravelPlan plan = travelPlan(owner);
        String token = mapLinks.create(owner.getId(), mapLinkDraft(plan)).token();
        travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), successor.getLoginId());
        kakao(owner, "823456789"); when(kakao.isUnlinkConfigured()).thenReturn(true);
        var cleanupFinished = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            cleanupFinished.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("withdrawal timed out");
            return invocation.callRealMethod();
        }).when(cipher).encrypt(any(), any(), eq(WithdrawalCleanupKind.KAKAO_UNLINK.name()), anyString());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var withdrawal = pool.submit(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of(), Map.of(plan.getId(), successor.getId())));
            assertThat(cleanupFinished.await(5, TimeUnit.SECONDS)).isTrue();
            var preview = pool.submit(() -> mapLinks.preview(token));
            assertThatThrownBy(() -> preview.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); withdrawal.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> preview.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
            assertThat(travelMapLinks.findByTokenAndActiveTrue(token)).isEmpty();
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void ordinaryAdministratorDeactivationKeepsExistingPublicTravelMapBehavior() {
        AppUser owner = user(); TravelPlan plan = travelPlan(owner);
        String token = mapLinks.create(owner.getId(), mapLinkDraft(plan)).token();
        AppUser disabled = users.findById(owner.getId()).orElseThrow();
        disabled.setActive(false); users.saveAndFlush(disabled);
        assertThat(mapLinks.preview(token)).isNotNull();
    }

    @Test void withdrawalClearsLoginPreviewOnlyWhenItUsesTheOwnersLink() {
        AppUser owner = user(), other = user(); TravelPlan ownPlan = travelPlan(owner), otherPlan = travelPlan(other);
        String ownToken = mapLinks.create(owner.getId(), mapLinkDraft(ownPlan)).token();
        String otherToken = mapLinks.create(other.getId(), mapLinkDraft(otherPlan)).token();
        travelSharing.shareCompletedPlan(owner.getId(), ownPlan.getId(), other.getLoginId());
        try {
            loginPreview.updateAdminSettings(new AdminLoginMapPreviewUpdateRequest(true, ownToken, false));
            assertThat(loginPreview.getPublicPreview().enabled()).isTrue();
            start.start(owner.getId(), "12345678", Map.of(), Map.of(), Map.of(ownPlan.getId(), other.getId()));
            assertThat(loginPreview.getPublicPreview().enabled()).isFalse();
            assertThat(loginPreview.getAdminSettings().enabled()).isFalse();
            assertThat(jdbc.queryForObject("select share_token from travel_login_map_preview_settings where setting_id = 1", String.class)).isNull();
            assertThat(travelMapLinks.findByTokenAndActiveTrue(otherToken)).isPresent();
            loginPreview.updateAdminSettings(new AdminLoginMapPreviewUpdateRequest(true, otherToken, false));
            assertThat(loginPreview.getPublicPreview().enabled()).isTrue();
        } finally {
            loginPreview.updateAdminSettings(new AdminLoginMapPreviewUpdateRequest(false, null, true));
        }
    }

    @Test void failedWithdrawalRestoresTheOwnedLoginPreviewSetting() {
        AppUser owner = user(), successor = user(); TravelPlan plan = travelPlan(owner);
        String token = mapLinks.create(owner.getId(), mapLinkDraft(plan)).token();
        travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), successor.getLoginId());
        kakao(owner, "923456789"); when(kakao.isUnlinkConfigured()).thenReturn(true);
        doThrow(new ServiceUnavailableException("test task-store failure")).when(cipher)
                .encrypt(any(), any(), eq(WithdrawalCleanupKind.KAKAO_UNLINK.name()), anyString());
        try {
            loginPreview.updateAdminSettings(new AdminLoginMapPreviewUpdateRequest(true, token, false));
            assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of(), Map.of(plan.getId(), successor.getId())))
                    .isInstanceOf(ServiceUnavailableException.class);
            assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
            assertThat(travelMapLinks.findByTokenAndActiveTrue(token)).isPresent();
            assertThat(jdbc.queryForObject("select share_token from travel_login_map_preview_settings where setting_id = 1", String.class)).isEqualTo(token);
            assertThat(loginPreview.getPublicPreview().enabled()).isTrue();
        } finally {
            loginPreview.updateAdminSettings(new AdminLoginMapPreviewUpdateRequest(false, null, true));
        }
    }

    @Test void withdrawalLeavesAnotherOwnersLoginPreviewUnchanged() {
        AppUser owner = user(), other = user(); TravelPlan ownPlan = travelPlan(owner), otherPlan = travelPlan(other);
        mapLinks.create(owner.getId(), mapLinkDraft(ownPlan));
        String otherToken = mapLinks.create(other.getId(), mapLinkDraft(otherPlan)).token();
        travelSharing.shareCompletedPlan(owner.getId(), ownPlan.getId(), other.getLoginId());
        try {
            loginPreview.updateAdminSettings(new AdminLoginMapPreviewUpdateRequest(true, otherToken, false));
            start.start(owner.getId(), "12345678", Map.of(), Map.of(), Map.of(ownPlan.getId(), other.getId()));
            assertThat(loginPreview.getPublicPreview().enabled()).isTrue();
            assertThat(jdbc.queryForObject("select share_token from travel_login_map_preview_settings where setting_id = 1", String.class))
                    .isEqualTo(otherToken);
            assertThat(travelMapLinks.findByTokenAndActiveTrue(otherToken)).isPresent();
        } finally {
            loginPreview.updateAdminSettings(new AdminLoginMapPreviewUpdateRequest(false, null, true));
        }
    }

    @Test void withdrawalAnonymizesOnlyAccessLogsForTheDepartingOwnersFiles() {
        AppUser owner = user(), other = user();
        var ownPublic = driveAccessLog(owner, "success");
        var ownDirect = driveAccessLog(owner, "shared_permission_denied");
        var unsafeStatus = driveAccessLog(owner, "user-private-free-text");
        var untouched = driveAccessLog(other, "success");

        start.start(owner.getId(), "12345678", Map.of(), Map.of());

        for (var original : List.of(ownPublic, ownDirect, unsafeStatus)) {
            var log = driveAccessLogRows.findById(original.getId()).orElseThrow();
            assertThat(log.getOwnerId()).isNull(); assertThat(log.getItemId()).isNull(); assertThat(log.getLinkId()).isNull();
            assertThat(log.getClientAddress()).isNull(); assertThat(log.getUserAgent()).isNull();
            assertThat(log.getTokenFingerprint()).isEqualTo(DriveDownloadLinkAccessLogService.ANONYMOUS_DIRECT_SHARE_FINGERPRINT);
            assertThat(log.getAccessedAt()).isEqualTo(original.getAccessedAt());
        }
        assertThat(driveAccessLogRows.findById(ownPublic.getId()).orElseThrow().getStatus()).isEqualTo("success");
        assertThat(driveAccessLogRows.findById(ownDirect.getId()).orElseThrow().getStatus()).isEqualTo("shared_permission_denied");
        assertThat(driveAccessLogRows.findById(unsafeStatus.getId()).orElseThrow().getStatus()).isEqualTo("unknown");
        var otherLog = driveAccessLogRows.findById(untouched.getId()).orElseThrow();
        assertThat(otherLog.getOwnerId()).isEqualTo(other.getId());
        assertThat(otherLog.getClientAddress()).isEqualTo("192.0.2.1");
        assertThat(otherLog.getUserAgent()).isEqualTo("test-browser");
    }

    @Test void failedWithdrawalRollsBackOwnedDriveAccessLogAnonymization() {
        AppUser owner = user(); var original = driveAccessLog(owner, "success");
        kakao(owner, "103456789"); when(kakao.isUnlinkConfigured()).thenReturn(true);
        doThrow(new ServiceUnavailableException("test task-store failure")).when(cipher)
                .encrypt(any(), any(), eq(WithdrawalCleanupKind.KAKAO_UNLINK.name()), anyString());
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(ServiceUnavailableException.class);
        var retained = driveAccessLogRows.findById(original.getId()).orElseThrow();
        assertThat(retained.getOwnerId()).isEqualTo(owner.getId());
        assertThat(retained.getItemId()).isEqualTo(44L); assertThat(retained.getLinkId()).isEqualTo(55L);
        assertThat(retained.getClientAddress()).isEqualTo("192.0.2.1");
    }

    @Test void travelBudgetWritesWaitingForWithdrawalCannotRecreateOrChangeData() throws Exception {
        AppUser owner = user();
        TravelPlan plan = travelPlan(owner);
        Draft initial = new Draft("식비", "기존 예산", BigDecimal.TEN, "KRW", null, null, 0);
        long itemId = travelBudgetWrites.create(owner.getId(), plan.getId(), initial).id();
        kakao(owner, "723456789");
        when(kakao.isUnlinkConfigured()).thenReturn(true);
        var cleanupFinished = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            cleanupFinished.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("withdrawal timed out");
            return invocation.callRealMethod();
        }).when(cipher).encrypt(any(), any(), eq(WithdrawalCleanupKind.KAKAO_UNLINK.name()), anyString());
        var pool = Executors.newFixedThreadPool(4);
        try {
            var withdrawal = pool.submit(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()));
            assertThat(cleanupFinished.await(5, TimeUnit.SECONDS)).isTrue();
            var create = pool.submit(() -> travelBudgetWrites.create(owner.getId(), plan.getId(), initial));
            var update = pool.submit(() -> travelBudgetWrites.update(owner.getId(), itemId,
                    new Draft("식비", "변경", BigDecimal.ONE, "KRW", null, null, 0)));
            var delete = pool.submit(() -> { travelBudgetWrites.delete(owner.getId(), itemId); return null; });
            assertThatThrownBy(() -> create.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThatThrownBy(() -> update.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThatThrownBy(() -> delete.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); withdrawal.get(10, TimeUnit.SECONDS);
            for (var operation : List.of(create, update, delete)) {
                assertThatThrownBy(() -> operation.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(NotFoundException.class);
            }
            assertThat(travelBudgetItems.findById(itemId)).isEmpty();
            assertThat(travelBudgetItems.findAllByPlanIdAndPlanOwnerIdOrderByDisplayOrderAscIdAsc(plan.getId(), owner.getId()))
                    .isEmpty();
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void withdrawalCapturesEncryptedTravelFileInventoryBeforeRemovingOnlyOwnedMetadata() throws Exception {
        AppUser owner = user(), other = user();
        TravelPlan plan = travelPlan(owner), otherPlan = travelPlan(other);
        TravelExpenseRecord record = travelRecord(plan), otherRecord = travelRecord(otherPlan);
        String file = UUID.randomUUID() + "-private-receipt.jpg";
        String photoPath = "travel-media/" + owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file;
        TravelMediaAsset asset = travelAsset(plan, record, owner, file, photoPath);
        String otherFile = UUID.randomUUID() + "-other.jpg";
        TravelMediaAsset otherAsset = travelAsset(otherPlan, otherRecord, other, otherFile,
                "travel-media/" + other.getId() + "/" + otherPlan.getId() + "/" + otherRecord.getId() + "/" + otherFile);
        TravelRouteSegment route = travelRoute(plan, "[]");
        String gpxPath = owner.getId() + "/" + plan.getId() + "/routes/" + route.getId() + "/" + UUID.randomUUID() + "-private.gpx";
        route.setGpxFilesJson(json.writeValueAsString(List.of(Map.of("originalFileName", "private.gpx",
                "storagePath", gpxPath, "contentType", "application/gpx+xml", "fileSize", 123))));
        travelRoutes.saveAndFlush(route);

        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());

        var inventory = tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())
                && task.getKind() == WithdrawalCleanupKind.TRAVEL_FILE_INVENTORY).toList();
        assertThat(inventory).hasSize(2).allSatisfy(task -> {
            assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
            assertThat(task.getEncryptedPayload()).doesNotContain(photoPath, gpxPath, "private");
            assertThat(task.getTaskKeyHash()).doesNotContain(photoPath, gpxPath);
        });
        var decoded = inventory.stream().map(task -> {
            try {
                return json.readTree(cipher.decrypt(requestId, UUID.fromString(task.getId()), task.getKind().name(), task.getEncryptedPayload()));
            } catch (Exception exception) { throw new AssertionError(exception); }
        }).toList();
        assertThat(decoded).extracting(node -> node.path("storagePath").asText()).containsExactlyInAnyOrder(photoPath, gpxPath);
        assertThat(decoded).extracting(node -> node.path("sourceType").asText()).containsExactlyInAnyOrder("MEDIA", "GPX");
        assertThat(travelMedia.findById(asset.getId())).isEmpty();
        assertThat(travelMedia.findById(otherAsset.getId())).isPresent();
        assertThat(travelRoutes.findById(route.getId())).isEmpty();
        assertThat(travelRecords.findById(record.getId())).isEmpty();
        assertThat(travelPlans.findById(plan.getId())).isEmpty();
        assertThat(travelPlans.findById(otherPlan.getId())).isPresent();
    }

    @Test void foreignRecordMapReferencesPreventDeletingAnotherUsersData() {
        AppUser owner = user(), other = user();
        TravelPlan plan = travelPlan(owner); TravelExpenseRecord record = travelRecord(plan);
        Long placeId = new TransactionTemplate(transactions).execute(status -> {
            RecordPlace place = new RecordPlace(); place.setOwnerId(other.getId()); place.setName("다른 사용자의 장소");
            place.setLatitude(37.5); place.setLongitude(127.0); place.setSourceTravelRecordId(record.getId());
            entities.persist(place); entities.flush(); return place.getId();
        });
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사용자가 참조");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(travelPlans.findById(plan.getId())).isPresent();
        assertThat(travelRecords.findById(record.getId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from record_map_places where id=?", Integer.class, placeId)).isEqualTo(1);
    }

    @Test void foreignLedgerPlanOrRecordReferencesPreventDeletingAnotherUsersData() {
        for (boolean referencePlan : List.of(true, false)) {
            AppUser owner = user(), other = user();
            TravelPlan plan = travelPlan(owner);
            TravelExpenseRecord record = travelRecord(plan);
            long entryId = ledger(other)[0];
            LedgerEntry entry = ledgerEntries.findById(entryId).orElseThrow();
            if (referencePlan) entry.setTravelPlanId(plan.getId());
            else entry.setTravelRecordId(record.getId());
            ledgerEntries.saveAndFlush(entry);

            assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사용자가 참조");
            assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
            assertThat(requests.findByUserId(owner.getId())).isEmpty();
            assertThat(travelPlans.findById(plan.getId())).isPresent();
            assertThat(travelRecords.findById(record.getId())).isPresent();
            assertThat(ledgerEntries.findById(entryId)).isPresent();
        }
    }

    @Test void foreignDriveTravelLinksPreventMetadataRemoval() {
        AppUser owner = user(), other = user(); TravelPlan plan = travelPlan(owner);
        TravelExpenseRecord record = travelRecord(plan); String file = UUID.randomUUID() + "-photo.jpg";
        String path = owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file;
        TravelMediaAsset photo = travelAsset(plan, record, owner, file, path);
        DriveItem otherLink = new DriveItem(); otherLink.setOwner(other); otherLink.setOriginalName("photo.jpg");
        otherLink.setStoredName(file); otherLink.setStoragePath(path); otherLink.setSourceType(TravelDriveLinkService.SOURCE_TRAVEL_MEDIA);
        otherLink.setSourceReference(photo.getId().toString()); driveItems.saveAndFlush(otherLink);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사용자가 참조");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(travelMedia.findById(photo.getId())).isPresent();
        assertThat(driveItems.findById(otherLink.getId())).isPresent();
    }

    @Test void foreignDriveMediaSourceReferencePreventsMetadataRemovalEvenWhenPathDiffers() {
        AppUser owner = user(), other = user(); TravelPlan plan = travelPlan(owner);
        TravelExpenseRecord record = travelRecord(plan); String file = UUID.randomUUID() + "-photo.jpg";
        String path = owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file;
        TravelMediaAsset photo = travelAsset(plan, record, owner, file, path);
        DriveItem otherLink = new DriveItem(); otherLink.setOwner(other); otherLink.setOriginalName("photo.jpg");
        otherLink.setStoredName(file); otherLink.setStoragePath("different/path.jpg");
        otherLink.setSourceType(TravelDriveLinkService.SOURCE_TRAVEL_MEDIA);
        otherLink.setSourceReference(photo.getId().toString()); driveItems.saveAndFlush(otherLink);

        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사용자가 참조");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(travelMedia.findById(photo.getId())).isPresent();
        assertThat(driveItems.findById(otherLink.getId())).isPresent();
    }

    @Test void foreignDriveVersionReferencingTravelPhotoPreventsMetadataRemoval() {
        AppUser owner = user(), other = user(); TravelPlan plan = travelPlan(owner);
        TravelExpenseRecord record = travelRecord(plan); String file = UUID.randomUUID() + "-photo.jpg";
        String path = owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file;
        TravelMediaAsset photo = travelAsset(plan, record, owner, file, path);
        DriveItem otherFile = new DriveItem(); otherFile.setOwner(other); otherFile.setOriginalName("current.jpg");
        otherFile.setStoredName("current.jpg"); otherFile.setStoragePath("drive/other/current.jpg");
        otherFile = driveItems.saveAndFlush(otherFile);
        DriveItemVersion version = new DriveItemVersion(); version.setOwner(other); version.setItem(otherFile);
        version.setVersionNumber(1); version.setOriginalName("photo.jpg"); version.setStoredName(file);
        version.setStoragePath(path); version.setContentType("image/jpeg"); version.setFileSize(123L);
        version = driveVersions.saveAndFlush(version);

        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사용자가 참조");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(travelMedia.findById(photo.getId())).isPresent();
        assertThat(driveVersions.findById(version.getId())).isPresent();
        assertThat(driveItems.findById(otherFile.getId())).isPresent();
    }

    @Test void foreignDriveGpxPathOrRouteReferencePreventsMetadataRemoval() throws Exception {
        for (boolean referenceByPath : List.of(true, false)) {
            AppUser owner = user(), other = user(); TravelPlan plan = travelPlan(owner);
            TravelRouteSegment route = travelRoute(plan, "[]");
            String path = owner.getId() + "/" + plan.getId() + "/routes/" + route.getId() + "/" + UUID.randomUUID() + "-route.gpx";
            if (referenceByPath) {
                route.setGpxFilesJson(json.writeValueAsString(List.of(Map.of("originalFileName", "route.gpx",
                        "storagePath", path, "contentType", "application/gpx+xml", "fileSize", 123))));
                travelRoutes.saveAndFlush(route);
            }
            DriveItem otherLink = new DriveItem(); otherLink.setOwner(other); otherLink.setOriginalName("route.gpx");
            otherLink.setStoredName("route.gpx"); otherLink.setStoragePath(referenceByPath ? path : "different/route.gpx");
            otherLink.setSourceType(referenceByPath ? "OTHER" : TravelDriveLinkService.SOURCE_TRAVEL_GPX);
            otherLink.setSourceReference(referenceByPath ? "other" : route.getId().toString()); driveItems.saveAndFlush(otherLink);

            assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사용자가 참조");
            assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
            assertThat(requests.findByUserId(owner.getId())).isEmpty();
            assertThat(travelRoutes.findById(route.getId())).isPresent();
            assertThat(driveItems.findById(otherLink.getId())).isPresent();
        }
    }

    @Test void foreignDriveReferencesBeyondFirstTravelInventoryBatchAreProtected() throws Exception {
        AppUser owner = user(), other = user(); TravelPlan plan = travelPlan(owner);
        TravelRouteSegment lastRoute = null;
        for (int i = 0; i < 101; i++) lastRoute = travelRoute(plan, "[]");
        DriveItem staleRouteLink = new DriveItem(); staleRouteLink.setOwner(other); staleRouteLink.setOriginalName("route.gpx");
        staleRouteLink.setStoredName("route.gpx"); staleRouteLink.setStoragePath("different/route.gpx");
        staleRouteLink.setSourceType(TravelDriveLinkService.SOURCE_TRAVEL_GPX);
        staleRouteLink.setSourceReference(lastRoute.getId().toString()); driveItems.saveAndFlush(staleRouteLink);

        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사용자가 참조");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(travelRoutes.findById(lastRoute.getId())).isPresent();
        assertThat(driveItems.findById(staleRouteLink.getId())).isPresent();

        AppUser anotherOwner = user(), anotherUser = user(); TravelPlan otherPlan = travelPlan(anotherOwner);
        TravelRouteSegment manyFilesRoute = travelRoute(otherPlan, "[]");
        List<Map<String, Object>> files = new ArrayList<>();
        String lastPath = null;
        for (int i = 0; i < 101; i++) {
            lastPath = anotherOwner.getId() + "/" + otherPlan.getId() + "/routes/" + manyFilesRoute.getId()
                    + "/" + UUID.randomUUID() + "-route.gpx";
            files.add(Map.of("originalFileName", "route.gpx", "storagePath", lastPath,
                    "contentType", "application/gpx+xml", "fileSize", 123));
        }
        manyFilesRoute.setGpxFilesJson(json.writeValueAsString(files)); travelRoutes.saveAndFlush(manyFilesRoute);
        DriveItem pathLink = new DriveItem(); pathLink.setOwner(anotherUser); pathLink.setOriginalName("route.gpx");
        pathLink.setStoredName("route.gpx"); pathLink.setStoragePath(lastPath); pathLink.setSourceType("OTHER");
        driveItems.saveAndFlush(pathLink);

        assertThatThrownBy(() -> start.start(anotherOwner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사용자가 참조");
        assertThat(users.findById(anotherOwner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(travelRoutes.findById(manyFilesRoute.getId())).isPresent();
        assertThat(driveItems.findById(pathLink.getId())).isPresent();
    }

    @Test void foreignClusterMembershipPreventsDeletingTheReferencedCluster() {
        AppUser owner = user(), other = user();
        ClusterFixture original = cluster(owner);
        Long foreignMember = new TransactionTemplate(transactions).execute(status -> {
            var member = new TravelPhotoClusterMember(); member.setOwnerId(other.getId());
            member.setClusterId(original.clusterId()); member.setMediaId(original.clusterId() + 1); member.setSortOrder(1);
            entities.persist(member); entities.flush(); return member.getId();
        });
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사용자가 참조");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from travel_photo_clusters where id=?", Integer.class, original.clusterId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from travel_photo_cluster_members where id=?", Integer.class, foreignMember)).isEqualTo(1);
    }

    @Test void malformedTravelGpxMetadataRollsBackEarlierCleanupAndAccountDeactivation() {
        AppUser owner = user(); TravelPlan plan = travelPlan(owner);
        TravelRouteSegment route = travelRoute(plan, "[{\"storagePath\":");
        long[] ownerPreferences = preferences(owner);

        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("여행 파일");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(layouts.findById(ownerPreferences[0])).isPresent();
        assertThat(travelRoutes.findById(route.getId())).isPresent();
    }

    @Test void withdrawalWaitsForCommittedTravelMediaBeforeCapturingItsPath() throws Exception {
        AppUser owner = user(); TravelPlan plan = travelPlan(owner); TravelExpenseRecord record = travelRecord(plan);
        String file = UUID.randomUUID() + "-concurrent.jpg";
        String path = "travel-media/" + owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file;
        var inserted = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var upload = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                users.lockForAccountSecurityUpdate(owner.getId()).orElseThrow();
                travelAsset(plan, record, owner, file, path);
                inserted.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("upload timed out");
                } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            }));
            assertThat(inserted.await(5, TimeUnit.SECONDS)).isTrue();
            var withdrawal = pool.submit(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()));
            assertThatThrownBy(() -> withdrawal.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); upload.get(10, TimeUnit.SECONDS);
            UUID requestId = withdrawal.get(10, TimeUnit.SECONDS);
            var queued = tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())
                    && task.getKind() == WithdrawalCleanupKind.TRAVEL_FILE_INVENTORY).toList();
            assertThat(queued).singleElement().satisfies(task -> {
                try {
                    var payload = json.readTree(cipher.decrypt(requestId, UUID.fromString(task.getId()), task.getKind().name(), task.getEncryptedPayload()));
                    assertThat(payload.path("storagePath").asText()).isEqualTo(path);
                } catch (Exception exception) { throw new AssertionError(exception); }
            });
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void travelFileInventoryContinuesAcrossPageBoundaries() {
        AppUser owner = user(); TravelPlan plan = travelPlan(owner); TravelExpenseRecord record = travelRecord(plan);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (int i = 0; i < 101; i++) {
                String file = UUID.randomUUID() + "-" + i + ".jpg";
                travelAsset(plan, record, owner, file,
                        owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file);
            }
        });
        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        assertThat(tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())
                && task.getKind() == WithdrawalCleanupKind.TRAVEL_FILE_INVENTORY)).hasSize(101);
    }

    @Test void travelFilesWithDifferentUploadersOrUnsafePathsStopWithdrawal() {
        AppUser owner = user(), other = user(); TravelPlan plan = travelPlan(owner);
        TravelExpenseRecord record = travelRecord(plan);
        String file = UUID.randomUUID() + "-photo.jpg";
        TravelMediaAsset asset = travelAsset(plan, record, other, file,
                "travel-media/" + owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("소유자");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(travelMedia.findById(asset.getId())).isPresent();
        asset.setUploadedBy(owner); asset.setStoragePath("../" + file); travelMedia.saveAndFlush(asset);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("여행 파일");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(travelMedia.findById(asset.getId())).isPresent();
    }

    @Test void transferredPlanCanInventoryVerifiedFormerOwnerPathsOnLaterWithdrawal() throws Exception {
        AppUser previous = user(), owner = user(); TravelPlan plan = travelPlan(owner);
        plan.setStorageOwnerHistoryJson("[" + previous.getId() + "]"); travelPlans.saveAndFlush(plan);
        TravelExpenseRecord record = travelRecord(plan);
        String file = UUID.randomUUID() + "-photo.jpg";
        String path = "travel-media/" + previous.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file;
        travelAsset(plan, record, owner, file, path);
        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        var inventory = tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())
                && task.getKind() == WithdrawalCleanupKind.TRAVEL_FILE_INVENTORY).toList();
        assertThat(inventory).singleElement().satisfies(task -> {
            try {
                var payload = json.readTree(cipher.decrypt(requestId, UUID.fromString(task.getId()), task.getKind().name(), task.getEncryptedPayload()));
                assertThat(payload.path("ownerId").asLong()).isEqualTo(owner.getId());
                assertThat(payload.path("storageOriginId").asLong()).isEqualTo(previous.getId());
                assertThat(payload.path("storagePath").asText()).isEqualTo(path);
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        assertThat(users.findById(previous.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test void inconsistentTravelShareOwnerStopsWithdrawalWithoutRemovingOtherUsersData() {
        AppUser departing = user(), owner = user(), recipient = user();
        TravelPlan plan = travelPlan(owner);
        TravelPlanShare broken = new TravelPlanShare(); broken.setPlan(plan); broken.setSharedBy(departing);
        broken.setRecipient(recipient); broken.setCreatedAt(LocalDateTime.now()); planShares.saveAndFlush(broken);

        assertThatThrownBy(() -> start.start(departing.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("여행 공유");
        assertThat(users.findById(departing.getId()).orElseThrow().isActive()).isTrue();
        assertThat(planShares.findById(broken.getId())).isPresent();
        assertThat(travelPlans.findById(plan.getId())).isPresent();
    }

    @Test void directlySharedTravelAndPhotosAreNeverDiscardedBeforeTransferIsReady() {
        AppUser owner = user(), recipient = user();
        TravelPlan plan = travelPlan(owner); TravelExpenseRecord record = travelRecord(plan);
        String file = UUID.randomUUID() + "-photo.jpg";
        TravelMediaAsset photo = travelAsset(plan, record, owner, file,
                owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file);
        long shareId = travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), recipient.getLoginId()).id();

        assertThat(travelPlanWithdrawal.requiredTransfers(owner.getId())).singleElement().satisfies(choice -> {
            assertThat(choice.planId()).isEqualTo(plan.getId());
            assertThat(choice.directlyShared()).isTrue();
            assertThat(choice.members()).singleElement().satisfies(candidate -> {
                assertThat(candidate.userId()).isEqualTo(recipient.getId());
                assertThat(candidate.eligible()).isTrue();
            });
        });
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("공유 여행");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(planShares.findById(shareId)).isPresent();
        assertThat(travelMedia.findById(photo.getId())).isPresent();
        assertThat(exhibits.receivedDetail(recipient.getId(), shareId).id()).isEqualTo(shareId);
    }

    @Test void publicOnlyTravelRequiresSharingWithAMemberBeforeWithdrawal() {
        AppUser owner = user(), successor = user();
        TravelPlan exhibit = travelPlan(owner), community = travelPlan(owner), linked = travelPlan(owner);
        exhibit.setPublicShared(true); exhibit.setPublicSharedAt(LocalDateTime.now()); travelPlans.saveAndFlush(exhibit);
        TravelExpenseRecord communityRecord = travelRecord(community);
        communityRecord.setSharedWithCommunity(true); travelRecords.saveAndFlush(communityRecord);
        TravelExpenseRecord linkedRecord = travelRecord(linked);
        String file = UUID.randomUUID() + "-public.jpg";
        String path = owner.getId() + "/" + linked.getId() + "/" + linkedRecord.getId() + "/" + file;
        TravelMediaAsset photo = travelAsset(linked, linkedRecord, owner, file, path);
        String token = mapLinks.create(owner.getId(), mapLinkDraft(linked)).token();
        assertThat(travelPlanWithdrawal.requiredTransfers(owner.getId())).hasSize(3)
                .allSatisfy(choice -> { assertThat(choice.publiclyShared()).isTrue(); assertThat(choice.members()).isEmpty(); });
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("먼저 이전받을 회원에게 공유");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(travelMapLinks.findByTokenAndActiveTrue(token)).isPresent();

        for (TravelPlan plan : List.of(exhibit, community, linked)) {
            travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), successor.getLoginId());
        }
        UUID request = start.start(owner.getId(), "12345678", Map.of(), Map.of(),
                Map.of(exhibit.getId(), successor.getId(), community.getId(), successor.getId(), linked.getId(), successor.getId()));
        for (TravelPlan plan : List.of(exhibit, community, linked)) {
            assertThat(travelPlans.findById(plan.getId()).orElseThrow().getOwner().getId()).isEqualTo(successor.getId());
            assertThat(travelPlans.findById(plan.getId()).orElseThrow().getPublicShared()).isFalse();
        }
        assertThat(travelRecords.findById(communityRecord.getId()).orElseThrow().getSharedWithCommunity()).isFalse();
        assertThat(travelMapLinks.findByTokenAndActiveTrue(token)).isEmpty();
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getStoragePath()).isEqualTo(path);
        assertThat(tasks.findAll().stream().filter(task -> task.getRequestId().equals(request.toString())
                && task.getKind() == WithdrawalCleanupKind.TRAVEL_FILE_INVENTORY)).isEmpty();
    }

    @Test void corruptedPublicMapScopeStopsWithdrawalWithoutDiscardingTheLink() {
        AppUser owner = user(); TravelPlan plan = travelPlan(owner);
        String token = mapLinks.create(owner.getId(), mapLinkDraft(plan)).token();
        jdbc.update("update travel_map_share_links set plan_ids_json = ? where token = ?", "{", token);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("공유 대상");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(travelMapLinks.findByTokenAndActiveTrue(token)).isPresent();
    }

    @Test void groupSharedTravelIsDetectedAndRequiresAMemberOfEveryExistingGroup() {
        AppUser owner = user(), groupMember = user(), directOnly = user();
        TravelPlan plan = travelPlan(owner);
        TravelShareGroup group = group(owner, groupMember);
        RecordShare groupShare = share(group, owner, groupMember, plan.getId());
        groupShare.setKind(RecordShareKind.TRAVEL); groupShare.setStatus(RecordShareStatus.ACCEPTED);
        shares.saveAndFlush(groupShare);
        long directId = travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), directOnly.getLoginId()).id();

        assertThat(travelPlanWithdrawal.requiredTransfers(owner.getId())).singleElement().satisfies(choice -> {
            assertThat(choice.planId()).isEqualTo(plan.getId());
            assertThat(choice.sharedGroupCount()).isEqualTo(1);
            assertThat(choice.members()).filteredOn(candidate -> candidate.userId().equals(groupMember.getId()))
                    .singleElement().satisfies(candidate -> assertThat(candidate.eligible()).isTrue());
            assertThat(choice.members()).filteredOn(candidate -> candidate.userId().equals(directOnly.getId()))
                    .singleElement().satisfies(candidate -> {
                        assertThat(candidate.eligible()).isFalse();
                        assertThat(candidate.unavailableReason()).contains("모든 공유 그룹");
                    });
        });
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of(group.getId(), groupMember.getId())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("공유 여행");
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(shares.findById(groupShare.getId())).isPresent();
        assertThat(planShares.findById(directId)).isPresent();
        assertThat(groups.findById(group.getId()).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
    }

    @Test void individualCleanupStepsAlsoProtectSharedTravelFromAnExistingWithdrawalRequest() {
        AppUser owner = user(), member = user(); TravelPlan plan = travelPlan(owner);
        TravelShareGroup group = group(owner, member);
        RecordShare groupShare = share(group, owner, member, plan.getId());
        groupShare.setKind(RecordShareKind.TRAVEL); shares.saveAndFlush(groupShare);
        long directId = travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), member.getLoginId()).id();
        owner.setActive(false); users.saveAndFlush(owner);
        UUID requestId = UUID.randomUUID();
        var request = new AccountWithdrawalRequest(); request.setId(requestId.toString()); request.setUserId(owner.getId());
        request.setState(AccountWithdrawalRequest.State.IN_PROGRESS); request.setRequestedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now()); requests.saveAndFlush(request);

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                recordShareCleanup.removeEnvelopesForInactiveUser(owner.getId())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("여행 계획과 사진");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                travelSharingCleanup.removeEdges(owner.getId(), requestId)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("여행 계획과 사진");
        assertThat(shares.findById(groupShare.getId())).isPresent();
        assertThat(planShares.findById(directId)).isPresent();
        assertThat(travelPlans.findById(plan.getId())).isPresent();
    }

    @Test void internalPlanTransferPreservesPhotosRoutesAndOtherRecipientsShares() {
        AppUser owner = user(), successor = user(), other = user();
        TravelPlan plan = travelPlan(owner); TravelExpenseRecord record = travelRecord(plan);
        String photoFile = UUID.randomUUID() + "-photo.jpg";
        String photoPath = "travel-media/" + owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + photoFile;
        TravelMediaAsset photo = travelAsset(plan, record, owner, photoFile, photoPath);
        TravelRouteSegment route = travelRoute(plan, null);
        String gpxPath = "travel-media/" + owner.getId() + "/" + plan.getId() + "/routes/" + route.getId() + "/" + UUID.randomUUID() + "-route.gpx";
        route.setGpxFilesJson("[{\"originalFileName\":\"route.gpx\",\"storagePath\":\"" + gpxPath
                + "\",\"contentType\":\"application/gpx+xml\",\"fileSize\":12}]");
        travelRoutes.saveAndFlush(route);
        long ownShare = travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), successor.getLoginId()).id();
        long otherShare = travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), other.getLoginId()).id();
        TravelShareGroup group = group(owner, successor, other);
        RecordShare accepted = share(group, owner, other, plan.getId());
        accepted.setKind(RecordShareKind.TRAVEL); accepted.setStatus(RecordShareStatus.ACCEPTED); shares.saveAndFlush(accepted);

        UUID requestId = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            beginInternalRequest(owner, requestId);
            groupTransfer.transferForWithdrawal(owner.getId(), Map.of(group.getId(), successor.getId()));
            travelPlanTransfer.transferForWithdrawal(owner.getId(), requestId, Map.of(plan.getId(), successor.getId()));
        });

        assertThat(travelPlans.findById(plan.getId()).orElseThrow().getOwner().getId()).isEqualTo(successor.getId());
        assertThat(travelPlans.findById(plan.getId()).orElseThrow().getStorageOwnerHistoryJson()).contains(owner.getId().toString());
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getUploadedBy().getId()).isEqualTo(successor.getId());
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getStoragePath()).isEqualTo(photoPath);
        assertThat(travelRoutes.findById(route.getId()).orElseThrow().getGpxFilesJson()).contains(gpxPath);
        assertThat(planShares.findById(ownShare)).isEmpty();
        assertThat(planShares.findById(otherShare).orElseThrow().getSharedBy().getId()).isEqualTo(successor.getId());
        assertThat(shares.findById(accepted.getId()).orElseThrow().getSender().getId()).isEqualTo(successor.getId());
        assertThat(exhibits.receivedDetail(other.getId(), otherShare).id()).isEqualTo(otherShare);
        assertThat(driveItems.findByOwner_IdAndSourceTypeAndSourceReference(successor.getId(),
                TravelDriveLinkService.SOURCE_TRAVEL_MEDIA, photo.getId().toString())).isPresent();
        assertThat(driveItems.findAllByOwner_IdAndSourceTypeAndSourceReference(successor.getId(),
                TravelDriveLinkService.SOURCE_TRAVEL_GPX, route.getId().toString())).hasSize(1);
    }

    @Test void internalPlanTransferRejectsInvalidSelectionsAndUnsafeFilePathsAtomically() {
        AppUser owner = user(), successor = user(), outsider = user();
        TravelPlan plan = travelPlan(owner); TravelExpenseRecord record = travelRecord(plan);
        String file = UUID.randomUUID() + "-photo.jpg";
        TravelMediaAsset photo = travelAsset(plan, record, owner, file, "../" + file);
        long share = travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), successor.getLoginId()).id();
        for (Map<Long, Long> selection : List.of(Map.<Long, Long>of(), Map.of(plan.getId(), outsider.getId()), Map.of(plan.getId(), successor.getId()))) {
            UUID requestId = UUID.randomUUID();
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                beginInternalRequest(owner, requestId);
                travelPlanTransfer.transferForWithdrawal(owner.getId(), requestId, selection);
            })).isInstanceOf(BadRequestException.class);
            assertThat(requests.findByUserId(owner.getId())).isEmpty();
            assertThat(travelPlans.findById(plan.getId()).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
            assertThat(planShares.findById(share)).isPresent();
            assertThat(travelMedia.findById(photo.getId()).orElseThrow().getStoragePath()).isEqualTo("../" + file);
        }
    }

    @Test void withdrawalTransfersSelectedTravelWithoutDeletingOtherRecipientsAccess() {
        AppUser owner = user(), successor = user(), other = user();
        TravelPlan plan = travelPlan(owner); TravelExpenseRecord record = travelRecord(plan);
        plan.setPublicShared(true); plan.setPublicSharedAt(LocalDateTime.now()); travelPlans.saveAndFlush(plan);
        record.setSharedWithCommunity(true); travelRecords.saveAndFlush(record);
        String file = UUID.randomUUID() + "-photo.jpg";
        String path = owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file;
        TravelMediaAsset photo = travelAsset(plan, record, owner, file, path);
        long successorShare = travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), successor.getLoginId()).id();
        long remainingShare = travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), other.getLoginId()).id();
        TravelShareGroup group = group(owner, successor, other);
        RecordShare accepted = share(group, owner, other, plan.getId());
        accepted.setKind(RecordShareKind.TRAVEL); accepted.setStatus(RecordShareStatus.ACCEPTED); shares.saveAndFlush(accepted);

        UUID request = start.start(owner.getId(), "12345678", Map.of(), Map.of(group.getId(), successor.getId()),
                Map.of(plan.getId(), successor.getId()));

        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isFalse();
        assertThat(travelPlans.findById(plan.getId()).orElseThrow().getOwner().getId()).isEqualTo(successor.getId());
        assertThat(travelPlans.findById(plan.getId()).orElseThrow().getPublicShared()).isFalse();
        assertThat(travelPlans.findById(plan.getId()).orElseThrow().getPublicSharedAt()).isNull();
        assertThat(travelRecords.findById(record.getId()).orElseThrow().getSharedWithCommunity()).isFalse();
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getStoragePath()).isEqualTo(path);
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getUploadedBy().getId()).isEqualTo(successor.getId());
        assertThat(planShares.findById(successorShare)).isEmpty();
        assertThat(exhibits.receivedDetail(other.getId(), remainingShare).id()).isEqualTo(remainingShare);
        assertThat(recordSharing.getTravel(other.getId(), accepted.getId()).travelPlan().id()).isEqualTo(plan.getId());
        assertThat(tasks.findAll().stream().filter(task -> task.getRequestId().equals(request.toString())
                && task.getKind() == WithdrawalCleanupKind.TRAVEL_FILE_INVENTORY)).isEmpty();
    }

    @Test void sharedTravelTransferPreservesPhotosUploadedByAnotherUser() {
        AppUser owner = user(), successor = user(), uploader = user();
        TravelPlan plan = travelPlan(owner); TravelExpenseRecord record = travelRecord(plan);
        String file = UUID.randomUUID() + "-photo.jpg";
        String path = owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file;
        TravelMediaAsset photo = travelAsset(plan, record, uploader, file, path);
        travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), successor.getLoginId());

        start.start(owner.getId(), "12345678", Map.of(), Map.of(), Map.of(plan.getId(), successor.getId()));

        assertThat(travelPlans.findById(plan.getId()).orElseThrow().getOwner().getId()).isEqualTo(successor.getId());
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getUploadedBy().getId()).isEqualTo(uploader.getId());
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getStoragePath()).isEqualTo(path);
        assertThat(users.findById(uploader.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test void withdrawingUploaderPreservesPhotoInAnotherUsersSharedTravel() {
        AppUser owner = user(), uploader = user(), recipient = user();
        TravelPlan plan = travelPlan(owner); TravelExpenseRecord record = travelRecord(plan);
        String file = UUID.randomUUID() + "-photo.jpg";
        String path = owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file;
        TravelMediaAsset photo = travelAsset(plan, record, uploader, file, path);
        String otherFile = UUID.randomUUID() + "-other.jpg";
        TravelMediaAsset otherPhoto = travelAsset(plan, record, owner, otherFile,
                owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + otherFile);
        long shareId = travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), recipient.getLoginId()).id();

        UUID requestId = start.start(uploader.getId(), "12345678", Map.of(), Map.of());

        assertThat(users.findById(uploader.getId()).orElseThrow().isActive()).isFalse();
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(travelPlans.findById(plan.getId()).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getUploadedBy().getId()).isEqualTo(owner.getId());
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getStoragePath()).isEqualTo(path);
        assertThat(travelMedia.findById(otherPhoto.getId())).isPresent();
        assertThat(planShares.findById(shareId)).isPresent();
        assertThat(exhibits.receivedDetail(recipient.getId(), shareId).id()).isEqualTo(shareId);
        assertThat(tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())
                && task.getKind() == WithdrawalCleanupKind.TRAVEL_FILE_INVENTORY)).isEmpty();
    }

    @Test void uploaderCanWithdrawAfterAnotherUsersTravelWasTransferred() {
        AppUser originalOwner = user(), successor = user(), uploader = user();
        TravelPlan plan = travelPlan(originalOwner); TravelExpenseRecord record = travelRecord(plan);
        String file = UUID.randomUUID() + "-photo.jpg";
        String path = originalOwner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file;
        TravelMediaAsset photo = travelAsset(plan, record, uploader, file, path);
        travelSharing.shareCompletedPlan(originalOwner.getId(), plan.getId(), successor.getLoginId());

        start.start(originalOwner.getId(), "12345678", Map.of(), Map.of(), Map.of(plan.getId(), successor.getId()));
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getUploadedBy().getId()).isEqualTo(uploader.getId());
        start.start(uploader.getId(), "12345678", Map.of(), Map.of());

        assertThat(travelPlans.findById(plan.getId()).orElseThrow().getOwner().getId()).isEqualTo(successor.getId());
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getUploadedBy().getId()).isEqualTo(successor.getId());
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getStoragePath()).isEqualTo(path);
        assertThat(users.findById(uploader.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test void withdrawingUploaderWaitsForLiveUploadGrantBeforePreservingPhoto() {
        AppUser owner = user(), uploader = user(); TravelPlan plan = travelPlan(owner);
        TravelExpenseRecord record = travelRecord(plan); String file = UUID.randomUUID() + "-photo.jpg";
        String path = owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file;
        TravelMediaAsset photo = travelAsset(plan, record, uploader, file, path);
        TravelUploadGrant grant = new TravelUploadGrant(); grant.setOwnerId(owner.getId()); grant.setPlanId(plan.getId());
        grant.setSourceId(record.getId()); grant.setKind(TravelUploadGrant.Kind.MEDIA); grant.setObjectKey(path);
        grant.setStorageBucket("isolated-test"); grant.setSignedAtUtc(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
        grant.setExpiresAtUtc(LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1)); grant = travelUploadGrants.saveAndFlush(grant);

        assertThatThrownBy(() -> start.start(uploader.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("업로드 주소");
        assertThat(users.findById(uploader.getId()).orElseThrow().isActive()).isTrue();
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getUploadedBy().getId()).isEqualTo(uploader.getId());
        assertThat(requests.findByUserId(uploader.getId())).isEmpty();

        grant.setSignedAtUtc(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(20));
        grant.setExpiresAtUtc(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(6)); travelUploadGrants.saveAndFlush(grant);
        start.start(uploader.getId(), "12345678", Map.of(), Map.of());
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getUploadedBy().getId()).isEqualTo(owner.getId());
        assertThat(travelUploadGrants.findById(grant.getId())).isPresent();
    }

    @Test void withdrawingUploaderCannotTransferPhotoToInactivePlanOwner() {
        AppUser owner = user(), uploader = user(); TravelPlan plan = travelPlan(owner);
        TravelExpenseRecord record = travelRecord(plan); String file = UUID.randomUUID() + "-photo.jpg";
        TravelMediaAsset photo = travelAsset(plan, record, uploader, file,
                owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file);
        owner.setActive(false); users.saveAndFlush(owner);

        assertThatThrownBy(() -> start.start(uploader.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사람의 여행");
        assertThat(users.findById(uploader.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(uploader.getId())).isEmpty();
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getUploadedBy().getId()).isEqualTo(uploader.getId());
    }

    @Test void lateFailureRollsBackForeignTravelPhotoPreservation() {
        AppUser owner = user(), uploader = user(); TravelPlan plan = travelPlan(owner);
        TravelExpenseRecord record = travelRecord(plan); String file = UUID.randomUUID() + "-photo.jpg";
        TravelMediaAsset photo = travelAsset(plan, record, uploader, file,
                owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + file);
        kakao(uploader, "323456798"); when(kakao.isUnlinkConfigured()).thenReturn(true);
        doThrow(new ServiceUnavailableException("test task-store failure")).when(cipher)
                .encrypt(any(), any(), eq(WithdrawalCleanupKind.KAKAO_UNLINK.name()), anyString());
        int refreshJobsBefore = jdbc.queryForObject("select count(*) from travel_photo_cluster_refresh_jobs where owner_id = ?",
                Integer.class, owner.getId());

        assertThatThrownBy(() -> start.start(uploader.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(users.findById(uploader.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(uploader.getId())).isEmpty();
        assertThat(travelMedia.findById(photo.getId()).orElseThrow().getUploadedBy().getId()).isEqualTo(uploader.getId());
        assertThat(jdbc.queryForObject("select count(*) from travel_photo_cluster_refresh_jobs where owner_id = ?",
                Integer.class, owner.getId())).isEqualTo(refreshJobsBefore);
    }

    @Test void inconsistentLiveTravelShareDoesNotGetSilentlyDeletedOnWithdrawal() {
        AppUser owner = user(), sender = user(), recipient = user();
        TravelPlan plan = travelPlan(owner); TravelShareGroup group = group(sender, owner, recipient);
        RecordShare wrongSender = share(group, sender, recipient, plan.getId());
        wrongSender.setKind(RecordShareKind.TRAVEL); shares.saveAndFlush(wrongSender);

        var choice = travelPlanWithdrawal.requiredTransfers(owner.getId());
        assertThat(choice).singleElement().satisfies(item -> assertThat(item.members())
                .allSatisfy(candidate -> assertThat(candidate.eligible()).isFalse()));
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of(), Map.of(plan.getId(), recipient.getId())))
                .isInstanceOf(BadRequestException.class);
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(shares.findById(wrongSender.getId())).isPresent();
        assertThat(travelPlans.findById(plan.getId())).isPresent();
    }

    @Test void staleRecipientUpdateCannotRestoreThePreTransferShareOwner() throws Exception {
        AppUser owner = user(), successor = user(), recipient = user();
        TravelPlan plan = travelPlan(owner); TravelShareGroup group = group(owner, successor, recipient);
        RecordShare share = share(group, owner, recipient, plan.getId());
        share.setKind(RecordShareKind.TRAVEL); shares.saveAndFlush(share);
        var requestEntities = entityManagerFactory.createEntityManager();
        var pool = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntities));
        try {
            RecordShare stale = requestEntities.find(RecordShare.class, share.getId());
            assertThat(stale.getSender().getId()).isEqualTo(owner.getId());
            pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                RecordShare current = entities.find(RecordShare.class, share.getId());
                current.setSender(entities.getReference(AppUser.class, successor.getId()));
            })).get(5, TimeUnit.SECONDS);
            assertThat(stale.getSender().getId()).isEqualTo(owner.getId());
            recordSharing.reject(recipient.getId(), share.getId());
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestEntities.close(); pool.shutdownNow();
        }
        assertThat(shares.findById(share.getId()).orElseThrow().getSender().getId()).isEqualTo(successor.getId());
        assertThat(shares.findById(share.getId()).orElseThrow().getStatus()).isEqualTo(RecordShareStatus.REJECTED);
    }

    @Test void travelTransferWaitsUntilKnownUploadAddressesExpire() {
        AppUser owner = user(), successor = user(); TravelPlan plan = travelPlan(owner);
        TravelExpenseRecord record = travelRecord(plan);
        long shared = travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), successor.getLoginId()).id();
        TravelUploadGrant grant = new TravelUploadGrant(); grant.setOwnerId(owner.getId()); grant.setPlanId(plan.getId());
        grant.setSourceId(record.getId()); grant.setKind(TravelUploadGrant.Kind.MEDIA);
        grant.setObjectKey("travel-media/" + owner.getId() + "/" + plan.getId() + "/" + record.getId() + "/" + UUID.randomUUID() + "-photo.jpg");
        grant.setStorageBucket("isolated-test"); grant.setSignedAtUtc(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
        grant.setExpiresAtUtc(LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1)); travelUploadGrants.saveAndFlush(grant);

        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of(), Map.of(plan.getId(), successor.getId())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("업로드 주소");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(travelPlans.findById(plan.getId()).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
        assertThat(planShares.findById(shared)).isPresent();
    }

    @Test void aRecipientWithAnExistingWithdrawalRequestCannotReceiveThePlan() {
        AppUser owner = user(), successor = user(); TravelPlan plan = travelPlan(owner);
        long shared = travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), successor.getLoginId()).id();
        new TransactionTemplate(transactions).executeWithoutResult(status -> beginInternalRequest(successor, UUID.randomUUID()));
        assertThat(travelPlanWithdrawal.requiredTransfers(owner.getId())).singleElement()
                .satisfies(choice -> assertThat(choice.members()).singleElement().satisfies(candidate -> {
                    assertThat(candidate.eligible()).isFalse();
                    assertThat(candidate.unavailableReason()).contains("탈퇴 처리");
                }));
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of(), Map.of(plan.getId(), successor.getId())))
                .isInstanceOf(BadRequestException.class);
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(planShares.findById(shared)).isPresent();
    }

    @Test void concurrentRecipientActionObservesTheTransferredShareOwner() throws Exception {
        AppUser owner = user(), successor = user(), recipient = user();
        TravelPlan plan = travelPlan(owner); TravelShareGroup group = group(owner, successor, recipient);
        RecordShare pending = share(group, owner, recipient, plan.getId());
        pending.setKind(RecordShareKind.TRAVEL); shares.saveAndFlush(pending);
        travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), successor.getLoginId());
        kakao(owner, "1223344556"); when(kakao.isUnlinkConfigured()).thenReturn(true);
        var transferred = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            transferred.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("transfer timed out");
            return invocation.callRealMethod();
        }).when(cipher).encrypt(any(), any(), eq(WithdrawalCleanupKind.KAKAO_UNLINK.name()), anyString());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var withdrawal = pool.submit(() -> start.start(owner.getId(), "12345678", Map.of(),
                    Map.of(group.getId(), successor.getId()), Map.of(plan.getId(), successor.getId())));
            assertThat(transferred.await(5, TimeUnit.SECONDS)).isTrue();
            var response = pool.submit(() -> recordSharing.reject(recipient.getId(), pending.getId()));
            assertThatThrownBy(() -> response.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); withdrawal.get(10, TimeUnit.SECONDS); response.get(10, TimeUnit.SECONDS);
            assertThat(shares.findById(pending.getId()).orElseThrow().getSender().getId()).isEqualTo(successor.getId());
            assertThat(shares.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(RecordShareStatus.REJECTED);
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void anOrdinarilyDisabledOwnerKeepsLegacyTravelExhibitAccess() {
        AppUser owner = user(), recipient = user();
        TravelPlan plan = travelPlan(owner);
        long share = travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), recipient.getLoginId()).id();
        AppUser disabled = users.findById(owner.getId()).orElseThrow();
        disabled.setActive(false); users.saveAndFlush(disabled);
        assertThat(exhibits.receivedDetail(recipient.getId(), share).id()).isEqualTo(share);
    }

    @Test void newTravelShareAndGroupCannotAddARecipientAfterWithdrawal() throws Exception {
        AppUser departing = user(), owner = user();
        TravelPlan plan = travelPlan(owner);
        kakao(departing, "523456789"); when(kakao.isUnlinkConfigured()).thenReturn(true);
        var cleanupFinished = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            cleanupFinished.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("withdrawal timed out");
            return invocation.callRealMethod();
        }).when(cipher).encrypt(any(), any(), eq(WithdrawalCleanupKind.KAKAO_UNLINK.name()), anyString());
        var pool = Executors.newFixedThreadPool(3);
        try {
            var withdrawal = pool.submit(() -> start.start(departing.getId(), "12345678", Map.of(), Map.of()));
            assertThat(cleanupFinished.await(5, TimeUnit.SECONDS)).isTrue();
            var share = pool.submit(() -> travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), departing.getLoginId()));
            var group = pool.submit(() -> travelSharing.saveGroup(owner.getId(), "신규 그룹", List.of(departing.getLoginId())));
            assertThatThrownBy(() -> share.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThatThrownBy(() -> group.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); withdrawal.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> share.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> group.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
            assertThat(planShares.findByPlanIdAndRecipientId(plan.getId(), departing.getId())).isEmpty();
            assertThat(groups.findAllByOwnerIdOrderByUpdatedAtDescIdDesc(owner.getId())).isEmpty();
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void travelExhibitRequestsWaitingForOwnerWithdrawalCannotOpenTheRevokedShare() throws Exception {
        AppUser departing = user(), recipient = user();
        TravelPlan plan = travelPlan(departing);
        long shareId = travelSharing.shareCompletedPlan(departing.getId(), plan.getId(), recipient.getLoginId()).id();
        kakao(departing, "623456789"); when(kakao.isUnlinkConfigured()).thenReturn(true);
        var cleanupFinished = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            cleanupFinished.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("withdrawal timed out");
            return invocation.callRealMethod();
        }).when(cipher).encrypt(any(), any(), eq(WithdrawalCleanupKind.KAKAO_UNLINK.name()), anyString());
        var pool = Executors.newFixedThreadPool(3);
        try {
            var withdrawal = pool.submit(() -> start.start(departing.getId(), "12345678", Map.of(), Map.of(),
                    Map.of(plan.getId(), recipient.getId())));
            assertThat(cleanupFinished.await(5, TimeUnit.SECONDS)).isTrue();
            var detail = pool.submit(() -> exhibits.receivedDetail(recipient.getId(), shareId));
            var media = pool.submit(() -> exhibits.receivedMedia(recipient.getId(), shareId, 123L));
            assertThatThrownBy(() -> detail.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThatThrownBy(() -> media.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); withdrawal.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> detail.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> media.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void withdrawalWaitsForCommittedTravelSharesThenRemovesOnlyDepartingMembership() throws Exception {
        AppUser owner = user(), departing = user(), retained = user();
        TravelPlan plan = travelPlan(owner);
        var created = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var sharing = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), departing.getLoginId());
                var group = travelSharing.saveGroup(owner.getId(), "동시 공유", List.of(departing.getLoginId(), retained.getLoginId()));
                created.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("share timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                }
                return group.id();
            }));
            assertThat(created.await(5, TimeUnit.SECONDS)).isTrue();
            var withdrawal = pool.submit(() -> start.start(departing.getId(), "12345678", Map.of(), Map.of()));
            assertThatThrownBy(() -> withdrawal.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            Long groupId = sharing.get(10, TimeUnit.SECONDS); withdrawal.get(10, TimeUnit.SECONDS);
            assertThat(planShares.findByPlanIdAndRecipientId(plan.getId(), departing.getId())).isEmpty();
            assertThat(groups.findById(groupId)).isPresent();
            assertThat(groupMembers.findAllByGroupIdOrderByIdAsc(groupId))
                    .extracting(member -> member.getMember().getId()).containsExactly(retained.getId());
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void withdrawalRemovesOnlyTheOwnersDerivedTravelClustersAndRejectsLateProjectionWrites() {
        AppUser departing = user(), other = user();
        ClusterFixture own = cluster(departing), retained = cluster(other);

        start.start(departing.getId(), "12345678", Map.of(), Map.of());

        assertThat(jdbc.queryForObject("select count(*) from travel_photo_clusters where id = ?", Integer.class, own.clusterId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from travel_photo_cluster_members where id = ?", Integer.class, own.memberId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from travel_photo_cluster_refresh_jobs where owner_id = ?", Integer.class, departing.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from travel_photo_clusters where id = ?", Integer.class, retained.clusterId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from travel_photo_cluster_members where id = ?", Integer.class, retained.memberId())).isEqualTo(1);
        assertThatThrownBy(() -> clusterProjection.apply(departing.getId(), List.of())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> clusterProjection.refreshSynchronously(departing.getId(), true)).isInstanceOf(NotFoundException.class);
    }

    @Test void clusterCleanupRejectsActiveAndOrdinarilyDisabledUsersButLegacyRefreshStillWorks() {
        AppUser active = user(), disabled = user();
        ClusterFixture activeCluster = cluster(active), disabledCluster = cluster(disabled);
        AppUser disabledUser = users.findById(disabled.getId()).orElseThrow();
        disabledUser.setActive(false); users.saveAndFlush(disabledUser);
        for (Long id : List.of(active.getId(), disabled.getId())) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                    travelClusterCleanup.removeDerivedData(id, UUID.randomUUID())))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("탈퇴 처리 중");
        }
        assertThat(jdbc.queryForObject("select count(*) from travel_photo_clusters where id = ?", Integer.class,
                activeCluster.clusterId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from travel_photo_clusters where id = ?", Integer.class,
                disabledCluster.clusterId())).isEqualTo(1);
        // A normal admin suspension does not by itself prohibit existing background refresh behavior.
        clusterProjection.apply(disabled.getId(), List.of());
        assertThat(jdbc.queryForObject("select count(*) from travel_photo_clusters where id = ?", Integer.class,
                disabledCluster.clusterId())).isZero();
    }

    @Test void staleInactiveUserCannotBypassCurrentAccountCheckInWithdrawalSteps() throws Exception {
        for (int variant = 0; variant < 20; variant++) {
            AppUser owner = user(), other = user();
            ClusterFixture projected = cluster(owner);
            DriveShareFixture direct = directShare(other, owner, null);
            TravelPlan plan = travelPlan(owner);
            long exhibit = travelSharing.shareCompletedPlan(owner.getId(), plan.getId(), other.getLoginId()).id();
            AppUser disabled = users.findById(owner.getId()).orElseThrow();
            disabled.setActive(false); users.saveAndFlush(disabled);
            UUID requestId = UUID.randomUUID();
            var request = new AccountWithdrawalRequest(); request.setId(requestId.toString()); request.setUserId(owner.getId());
            request.setState(AccountWithdrawalRequest.State.IN_PROGRESS); request.setRequestedAt(LocalDateTime.now());
            request.setUpdatedAt(LocalDateTime.now()); requests.saveAndFlush(request);
            var requestEntityManager = entityManagerFactory.createEntityManager();
            var pool = Executors.newSingleThreadExecutor();
            TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntityManager));
            try {
                AppUser stale = requestEntityManager.find(AppUser.class, owner.getId());
                assertThat(stale.isActive()).isFalse();
                pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                        users.lockForAccountSecurityUpdate(owner.getId()).orElseThrow().setActive(true)))
                        .get(5, TimeUnit.SECONDS);
                assertThat(stale.isActive()).isFalse();
                int selected = variant;
                assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                    switch (selected) {
                        case 0 -> travelClusterCleanup.removeDerivedData(owner.getId(), requestId);
                        case 1 -> travelSharingCleanup.removeEdges(owner.getId(), requestId);
                        case 2 -> driveShareCleanup.removeDirectShares(owner.getId(), requestId);
                        case 3 -> preferenceCleanup.removePreferencesAndGoals(owner.getId());
                        case 4 -> classificationCleanup.removeRules(owner.getId());
                        case 5 -> familyCleanup.scheduleAndRemove(owner.getId(), requestId);
                        case 6 -> imageCleanup.scheduleAndRemove(owner.getId(), requestId);
                        case 7 -> recordMapCleanup.scheduleAndRemove(owner.getId(), requestId);
                        case 8 -> supportCleanup.scheduleAndRemove(owner.getId(), requestId);
                        case 9 -> publicShareCleanup.suspend(owner.getId(), LocalDateTime.now());
                        case 10 -> auditCleanup.anonymize(owner.getId());
                        case 11 -> ledgerCleanup.removeEntriesAndHistories(owner.getId());
                        case 12 -> recurringCleanup.removeRulesAndOccurrences(owner.getId());
                        case 13 -> recordShareCleanup.removeEnvelopesForInactiveUser(owner.getId());
                        case 14 -> travelInventory.capture(owner.getId(), requestId);
                        case 15 -> driveAccessCleanup.anonymize(owner.getId(), requestId);
                        case 16 -> driveProfileCleanup.captureAndRemoveSettings(owner.getId(), requestId);
                        case 17 -> driveInventory.capture(owner.getId(), requestId);
                        case 18 -> driveMetadataCleanup.captureAndRemove(owner.getId(), requestId);
                        case 19 -> familyUploadInventory.captureAndRemove(owner.getId(), requestId);
                        default -> throw new AssertionError("Unexpected cleanup step");
                    }
                })).isInstanceOf(BadRequestException.class).hasMessageContaining("탈퇴 처리 중");
            } finally {
                TransactionSynchronizationManager.unbindResource(entityManagerFactory);
                requestEntityManager.close(); pool.shutdownNow();
            }
            assertThat(jdbc.queryForObject("select count(*) from travel_photo_clusters where id = ?", Integer.class,
                    projected.clusterId())).isEqualTo(1);
            assertThat(planShares.findById(exhibit)).isPresent();
            assertThat(driveShares.findById(direct.departingShare())).isPresent();
        }
    }

    @Test void withdrawalInventoriesDriveFilesAndVersionsBeforeRemovingOnlyOwnedMetadata() throws Exception {
        AppUser owner = user(), other = user();
        DriveItem file = new DriveItem(); file.setOwner(owner); file.setOriginalName("current.pdf");
        file.setStoredName("current.pdf"); file.setStoragePath("drive/" + owner.getId() + "/current.pdf");
        file.setStorageBucket("current-bucket");
        file.setFileSize(12); file = driveItems.saveAndFlush(file);
        DriveItemVersion version = new DriveItemVersion(); version.setOwner(owner); version.setItem(file);
        version.setVersionNumber(1); version.setOriginalName("old.pdf"); version.setStoredName("old.pdf");
        version.setStoragePath("drive/" + owner.getId() + "/old.pdf"); version.setContentType("application/pdf");
        version.setStorageBucket("previous-bucket");
        version.setFileSize(9L); version = driveVersions.saveAndFlush(version);
        DriveItemVersion restored = new DriveItemVersion(); restored.setOwner(owner); restored.setItem(file);
        restored.setVersionNumber(2); restored.setOriginalName("current.pdf"); restored.setStoredName("current.pdf");
        restored.setStoragePath(file.getStoragePath()); restored.setContentType("application/pdf");
        restored.setStorageBucket("current-bucket");
        restored.setFileSize(12L); restored = driveVersions.saveAndFlush(restored);
        DriveItem otherFile = new DriveItem(); otherFile.setOwner(other); otherFile.setOriginalName("other.pdf");
        otherFile.setStoredName("other.pdf"); otherFile.setStoragePath("drive/" + other.getId() + "/other.pdf");
        otherFile = driveItems.saveAndFlush(otherFile);
        DriveItem sharedObject = new DriveItem(); sharedObject.setOwner(other); sharedObject.setOriginalName("retained.pdf");
        sharedObject.setStoredName("retained.pdf"); sharedObject.setStoragePath(file.getStoragePath());
        sharedObject = driveItems.saveAndFlush(sharedObject);
        DriveDownloadLink ownLink = new DriveDownloadLink(); ownLink.setOwner(owner); ownLink.setItem(file);
        ownLink.setToken(UUID.randomUUID().toString()); ownLink.setExpiresAt(LocalDateTime.now().plusDays(1));
        ownLink = driveLinks.saveAndFlush(ownLink);
        DriveDownloadLink otherLink = new DriveDownloadLink(); otherLink.setOwner(other); otherLink.setItem(otherFile);
        otherLink.setToken(UUID.randomUUID().toString()); otherLink.setExpiresAt(LocalDateTime.now().plusDays(1));
        otherLink = driveLinks.saveAndFlush(otherLink);

        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        var inventory = tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())
                && task.getKind() == WithdrawalCleanupKind.DRIVE_FILE_INVENTORY).toList();
        assertThat(inventory).hasSize(3);
        List<String> paths = new java.util.ArrayList<>();
        for (var task : inventory) {
            assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
            String payload = cipher.decrypt(requestId, UUID.fromString(task.getId()), task.getKind().name(), task.getEncryptedPayload());
            var stored = json.readTree(payload);
            assertThat(stored.get("ownerId").longValue()).isEqualTo(owner.getId());
            assertThat(stored.get("storageBucket").asText()).isIn("current-bucket", "previous-bucket");
            if (stored.get("storagePath").isNull()) paths.add(null);
            else {
                assertThat(task.getEncryptedPayload()).doesNotContain(stored.get("storagePath").asText());
                paths.add(stored.get("storagePath").asText());
            }
        }
        assertThat(paths).containsExactlyInAnyOrder(file.getStoragePath(), version.getStoragePath(), file.getStoragePath());
        assertThat(driveItems.findById(file.getId())).isEmpty();
        assertThat(driveVersions.findById(version.getId())).isEmpty();
        assertThat(driveVersions.findById(restored.getId())).isEmpty();
        assertThat(driveItems.findById(otherFile.getId())).isPresent();
        assertThat(driveItems.findById(sharedObject.getId()).orElseThrow().getStoragePath()).isEqualTo(file.getStoragePath());
        assertThat(driveLinks.findById(ownLink.getId())).isEmpty();
        assertThat(driveLinks.findById(otherLink.getId()).orElseThrow().getRevokedAt()).isNull();
    }

    @Test void missingDrivePathStopsWithdrawalWithoutDiscardingMetadata() {
        for (String path : java.util.Arrays.asList(null, " \t", "drive/abc/\nfile.pdf")) {
            AppUser owner = user();
            DriveItem missing = new DriveItem(); missing.setOwner(owner); missing.setOriginalName("legacy.pdf");
            missing.setStoredName("legacy.pdf"); missing.setStoragePath(path); missing = driveItems.saveAndFlush(missing);
            assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("드라이브 파일");
            assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
            assertThat(requests.findByUserId(owner.getId())).isEmpty();
            assertThat(driveItems.findById(missing.getId())).isPresent();
        }
    }

    @Test void withdrawalInventoriesOutstandingUploadGrantsBeforeRemovingTheirRows() throws Exception {
        AppUser owner = user(), other = user();
        LocalDateTime signedAt = LocalDateTime.of(2026, 11, 3, 1, 2, 3);
        DriveUploadGrant own = new DriveUploadGrant(); own.setOwnerId(owner.getId());
        own.setObjectKey("drive/" + owner.getId() + "/pending.png"); own.setStorageBucket("drive-bucket");
        own.setSignedAtUtc(signedAt); own.setExpiresAtUtc(signedAt.plusMinutes(10)); own = driveUploadGrants.saveAndFlush(own);
        DriveUploadGrant retained = new DriveUploadGrant(); retained.setOwnerId(other.getId());
        retained.setObjectKey("drive/" + other.getId() + "/pending.png"); retained.setStorageBucket("other-bucket");
        retained.setSignedAtUtc(signedAt); retained.setExpiresAtUtc(signedAt.plusMinutes(10)); retained = driveUploadGrants.saveAndFlush(retained);

        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        assertThat(driveUploadGrants.findById(own.getId())).isEmpty();
        assertThat(driveUploadGrants.findById(retained.getId())).isPresent();
        var inventory = tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())
                && task.getKind() == WithdrawalCleanupKind.DRIVE_UPLOAD_GRANT_INVENTORY).toList();
        assertThat(inventory).singleElement().satisfies(task -> {
            try {
                var payload = json.readValue(cipher.decrypt(requestId, UUID.fromString(task.getId()),
                        task.getKind().name(), task.getEncryptedPayload()), DriveWithdrawalInventory.UploadGrant.class);
                assertThat(payload.ownerId()).isEqualTo(owner.getId());
                assertThat(payload.storageBucket()).isEqualTo("drive-bucket");
                assertThat(payload.objectKey()).isEqualTo("drive/" + owner.getId() + "/pending.png");
                assertThat(payload.expiresAtUtc()).isEqualTo(signedAt.plusMinutes(10));
                assertThat(task.getEncryptedPayload()).doesNotContain(payload.objectKey(), "drive-bucket");
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    @Test void malformedOutstandingGrantStopsWithdrawalAndPreservesItsRow() {
        AppUser owner = user();
        DriveUploadGrant grant = new DriveUploadGrant(); grant.setOwnerId(owner.getId());
        grant.setObjectKey("drive/another-user/file.png"); grant.setStorageBucket("bucket");
        grant.setSignedAtUtc(LocalDateTime.now()); grant.setExpiresAtUtc(LocalDateTime.now().plusMinutes(10));
        grant = driveUploadGrants.saveAndFlush(grant);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("업로드 주소");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(driveUploadGrants.findById(grant.getId())).isPresent();
    }

    @Test void withdrawalInventoriesFamilyUploadGrantsButPreservesTransferredSharedPhotos() {
        AppUser owner = user(), recipient = user();
        FamilyCategory category = family(owner, recipient);
        FamilyMediaAsset photo = familyPhoto(category, owner);
        LocalDateTime signedAt = LocalDateTime.of(2026, 11, 3, 1, 2, 3);
        FamilyMediaUploadGrant grant = new FamilyMediaUploadGrant(); grant.setOwnerId(owner.getId());
        grant.setCategoryId(category.getId()); grant.setObjectKey(photo.getStoragePath());
        grant.setStorageBucket("family-bucket"); grant.setSignedAtUtc(signedAt); grant.setExpiresAtUtc(signedAt.plusMinutes(10));
        grant = familyUploadGrants.saveAndFlush(grant);
        FamilyMediaUploadGrant retained = new FamilyMediaUploadGrant(); retained.setOwnerId(recipient.getId());
        retained.setCategoryId(category.getId()); retained.setObjectKey("family-media/" + recipient.getId() + "/" + category.getId() + "/other.jpg");
        retained.setStorageBucket("family-bucket"); retained.setSignedAtUtc(signedAt); retained.setExpiresAtUtc(signedAt.plusMinutes(10));
        retained = familyUploadGrants.saveAndFlush(retained);

        UUID requestId = start.start(owner.getId(), "12345678", Map.of(category.getId(), recipient.getId()), Map.of());
        assertThat(familyMedia.findById(photo.getId()).orElseThrow().getOwner().getId()).isEqualTo(recipient.getId());
        assertThat(familyMedia.findById(photo.getId()).orElseThrow().getStoragePath()).isEqualTo(photo.getStoragePath());
        assertThat(familyUploadGrants.findById(grant.getId())).isEmpty();
        assertThat(familyUploadGrants.findById(retained.getId())).isPresent();
        assertThat(tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())
                && task.getKind() == WithdrawalCleanupKind.FAMILY_UPLOAD_GRANT_INVENTORY))
                .singleElement().satisfies(task -> {
                    try {
                        var payload = json.readValue(cipher.decrypt(requestId, UUID.fromString(task.getId()),
                                task.getKind().name(), task.getEncryptedPayload()), FamilyUploadWithdrawalInventory.UploadGrant.class);
                        assertThat(payload.categoryId()).isEqualTo(category.getId());
                        assertThat(payload.objectKey()).isEqualTo(photo.getStoragePath());
                        assertThat(payload.storageBucket()).isEqualTo("family-bucket");
                        assertThat(payload.expiresAtUtc()).isEqualTo(signedAt.plusMinutes(10));
                    } catch (Exception failure) { throw new AssertionError(failure); }
                });
    }

    @Test void malformedFamilyGrantRollsBackAlbumAndPhotoTransfer() {
        AppUser owner = user(), recipient = user();
        FamilyCategory category = family(owner, recipient); FamilyMediaAsset photo = familyPhoto(category, owner);
        FamilyMediaUploadGrant grant = new FamilyMediaUploadGrant(); grant.setOwnerId(owner.getId());
        grant.setCategoryId(category.getId()); grant.setObjectKey("family-media/other/invalid.png"); grant.setStorageBucket("bucket");
        grant.setSignedAtUtc(LocalDateTime.now()); grant.setExpiresAtUtc(LocalDateTime.now().plusMinutes(10));
        grant = familyUploadGrants.saveAndFlush(grant);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(category.getId(), recipient.getId()), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("가족 사진 업로드");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(familyCategories.findById(category.getId()).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
        assertThat(familyMedia.findById(photo.getId()).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
        assertThat(familyUploadGrants.findById(grant.getId())).isPresent();
    }

    @Test void travelUploadInventoryRetainsEncryptedExpiryAndDoesNotTouchOtherOwners() {
        AppUser owner = user(), other = user();
        LocalDateTime signed = LocalDateTime.of(2026, 11, 3, 1, 2, 3);
        String original = "old-prefix/" + owner.getId() + "/9/10/photo.jpg";
        TravelUploadGrant thumb = new TravelUploadGrant(); thumb.setOwnerId(owner.getId()); thumb.setPlanId(9L); thumb.setSourceId(10L);
        thumb.setKind(TravelUploadGrant.Kind.THUMBNAIL); thumb.setOriginalKey(original);
        thumb.setObjectKey("old-prefix/" + owner.getId() + "/9/10/.thumbs/480/photo.jpg");
        thumb.setStorageBucket("old-travel-bucket"); thumb.setSignedAtUtc(signed); thumb.setExpiresAtUtc(signed.plusMinutes(10));
        thumb = travelUploadGrants.saveAndFlush(thumb);
        TravelUploadGrant retained = new TravelUploadGrant(); retained.setOwnerId(other.getId()); retained.setPlanId(9L); retained.setSourceId(10L);
        retained.setKind(TravelUploadGrant.Kind.MEDIA); retained.setObjectKey("travel-media/" + other.getId() + "/9/10/photo.jpg");
        retained.setStorageBucket("other-bucket"); retained.setSignedAtUtc(signed); retained.setExpiresAtUtc(signed.plusMinutes(10));
        retained = travelUploadGrants.saveAndFlush(retained);
        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        assertThat(travelUploadGrants.findById(thumb.getId())).isEmpty();
        assertThat(travelUploadGrants.findById(retained.getId())).isPresent();
        assertThat(tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())
                && task.getKind() == WithdrawalCleanupKind.TRAVEL_UPLOAD_GRANT_INVENTORY))
                .singleElement().satisfies(task -> {
                    try {
                        var payload = json.readValue(cipher.decrypt(requestId, UUID.fromString(task.getId()),
                                task.getKind().name(), task.getEncryptedPayload()), TravelUploadWithdrawalInventory.UploadGrant.class);
                        assertThat(payload.kind()).isEqualTo(TravelUploadGrant.Kind.THUMBNAIL);
                        assertThat(payload.originalKey()).isEqualTo(original);
                        assertThat(payload.storageBucket()).isEqualTo("old-travel-bucket");
                        assertThat(payload.expiresAtUtc()).isEqualTo(signed.plusMinutes(10));
                        assertThat(task.getEncryptedPayload()).doesNotContain(original, "old-travel-bucket");
                    } catch (Exception failure) { throw new AssertionError(failure); }
                });
    }

    @Test void invalidTravelUploadInventoryRollsBackTheWholeWithdrawal() {
        AppUser owner = user();
        TravelUploadGrant grant = new TravelUploadGrant(); grant.setOwnerId(owner.getId()); grant.setPlanId(9L); grant.setSourceId(10L);
        grant.setKind(TravelUploadGrant.Kind.MEDIA); grant.setObjectKey("travel-media/other/9/10/photo.jpg");
        grant.setStorageBucket("bucket"); grant.setSignedAtUtc(LocalDateTime.now()); grant.setExpiresAtUtc(LocalDateTime.now().plusMinutes(10));
        grant = travelUploadGrants.saveAndFlush(grant);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("여행 파일 업로드");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(travelUploadGrants.findById(grant.getId())).isPresent();
    }

    @Test void inconsistentDriveParentOwnershipRollsBackWithdrawal() {
        AppUser owner = user(), other = user();
        DriveItem parent = new DriveItem(); parent.setOwner(other); parent.setItemType(DriveItemType.FOLDER);
        parent.setOriginalName("other folder"); parent.setStoredName("other folder"); parent = driveItems.saveAndFlush(parent);
        DriveItem child = new DriveItem(); child.setOwner(owner); child.setParent(parent);
        child.setOriginalName("file.pdf"); child.setStoredName("file.pdf"); child = driveItems.saveAndFlush(child);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("소유자가 일치하지 않는 드라이브");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(driveItems.findById(parent.getId())).isPresent();
        assertThat(driveItems.findById(child.getId())).isPresent();
    }

    @Test void driveInventoryContinuesPastTheFirstPage() {
        AppUser owner = user();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var managedOwner = users.findById(owner.getId()).orElseThrow();
            for (int index = 0; index < 101; index++) {
                var file = new DriveItem(); file.setOwner(managedOwner); file.setOriginalName("file-" + index);
                file.setStoredName("file-" + index); file.setStoragePath("drive/" + owner.getId() + "/file-" + index);
                entities.persist(file);
            }
            entities.flush();
        });
        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        assertThat(tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())
                && task.getKind() == WithdrawalCleanupKind.DRIVE_FILE_INVENTORY)).hasSize(101);
        assertThat(driveItems.findAllByOwner_Id(owner.getId())).isEmpty();
    }

    @Test void conflictingDriveLinkOwnerStopsWithdrawalWithoutTouchingEitherUsersData() {
        AppUser owner = user(), other = user();
        DriveItem file = new DriveItem(); file.setOwner(owner); file.setOriginalName("file.pdf");
        file.setStoredName("file.pdf"); file.setStoragePath("drive/" + owner.getId() + "/file.pdf");
        file = driveItems.saveAndFlush(file);
        DriveDownloadLink link = new DriveDownloadLink(); link.setOwner(other); link.setItem(file);
        link.setToken(UUID.randomUUID().toString()); link.setExpiresAt(LocalDateTime.now().plusDays(1));
        link = driveLinks.saveAndFlush(link);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("드라이브 파일");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(driveItems.findById(file.getId())).isPresent();
        assertThat(driveLinks.findById(link.getId())).isPresent();
    }

    @Test void rolledBackWithdrawalRestoresDriveMetadataAndRevokedLinks() {
        AppUser owner = user();
        DriveItem folder = new DriveItem(); folder.setOwner(owner); folder.setItemType(DriveItemType.FOLDER);
        folder.setOriginalName("folder"); folder.setStoredName("folder"); folder = driveItems.saveAndFlush(folder);
        DriveItem file = new DriveItem(); file.setOwner(owner); file.setParent(folder); file.setOriginalName("file.pdf");
        file.setStoredName("file.pdf"); file.setStoragePath("drive/" + owner.getId() + "/file.pdf"); file = driveItems.saveAndFlush(file);
        DriveItemVersion version = new DriveItemVersion(); version.setOwner(owner); version.setItem(file);
        version.setVersionNumber(1); version.setOriginalName("file.pdf"); version.setStoredName("file.pdf");
        version.setStoragePath(file.getStoragePath()); version.setContentType("application/pdf"); version.setFileSize(1L);
        version = driveVersions.saveAndFlush(version);
        DriveDownloadLink link = new DriveDownloadLink(); link.setOwner(owner); link.setItem(file);
        link.setToken(UUID.randomUUID().toString()); link.setExpiresAt(LocalDateTime.now().plusDays(1));
        link = driveLinks.saveAndFlush(link);
        final UUID[] requestId = new UUID[1];
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            requestId[0] = start.start(owner.getId(), "12345678", Map.of(), Map.of());
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("rollback");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(driveItems.findById(folder.getId())).isPresent();
        assertThat(driveItems.findById(file.getId())).isPresent();
        assertThat(driveVersions.findById(version.getId())).isPresent();
        assertThat(driveLinks.findById(link.getId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId[0].toString()))).isEmpty();
    }

    @Test void inconsistentDriveVersionOwnershipRollsBackWithdrawal() {
        AppUser owner = user(), other = user();
        DriveItem file = new DriveItem(); file.setOwner(owner); file.setOriginalName("file.pdf");
        file.setStoredName("file.pdf"); file.setStoragePath("drive/" + owner.getId() + "/file.pdf");
        file = driveItems.saveAndFlush(file);
        DriveItemVersion version = new DriveItemVersion(); version.setOwner(other); version.setItem(file);
        version.setVersionNumber(1); version.setOriginalName("file.pdf"); version.setStoredName("file.pdf");
        version.setStoragePath(file.getStoragePath()); version.setContentType("application/pdf"); version.setFileSize(1L);
        version = driveVersions.saveAndFlush(version);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("소유자가 일치하지 않는 드라이브");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(driveItems.findById(file.getId())).isPresent();
        assertThat(driveVersions.findById(version.getId())).isPresent();
    }

    @Test void travelDriveBackfillWaitingForWithdrawalCannotRecreateFolders() throws Exception {
        AppUser owner = user(), other = user();
        TravelPlan plan = travelPlan(owner), otherPlan = travelPlan(other);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var withdrawal = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
                started.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("withdrawal timed out"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                return requestId;
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var backfill = pool.submit(() -> travelDriveLinks.ensurePlanFolder(plan));
            assertThatThrownBy(() -> backfill.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); withdrawal.get(10, TimeUnit.SECONDS); backfill.get(10, TimeUnit.SECONDS);
            assertThat(driveItems.findAllByOwner_Id(owner.getId())).isEmpty();
            travelDriveLinks.ensurePlanFolder(otherPlan);
            assertThat(driveItems.findAllByOwner_Id(other.getId())).isNotEmpty();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void travelDriveBackfillStillWorksForOrdinarilyDisabledAccounts() {
        AppUser owner = user(); TravelPlan plan = travelPlan(owner);
        var disabled = users.findById(owner.getId()).orElseThrow();
        disabled.setActive(false); users.saveAndFlush(disabled);
        travelDriveLinks.ensurePlanFolder(plan);
        assertThat(driveItems.findAllByOwner_Id(owner.getId())).isNotEmpty();
    }

    @Test void travelDriveBackfillResumesWhenTheWithdrawalRollsBack() throws Exception {
        AppUser owner = user(); TravelPlan plan = travelPlan(owner);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var withdrawal = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                start.start(owner.getId(), "12345678", Map.of(), Map.of());
                started.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("withdrawal timed out"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                throw new IllegalStateException("rollback");
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var backfill = pool.submit(() -> travelDriveLinks.ensurePlanFolder(plan));
            assertThatThrownBy(() -> backfill.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThatThrownBy(() -> withdrawal.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            backfill.get(10, TimeUnit.SECONDS);
            assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
            assertThat(driveItems.findAllByOwner_Id(owner.getId())).isNotEmpty();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void withdrawalCapturesOnlyTheDepartingUsersProfileImageReference() {
        AppUser owner = user(), other = user();
        driveProfileService.getSettings(owner.getId());
        driveProfileService.getSettings(other.getId());
        var ownerSettings = driveProfileSettings.findByUser_Id(owner.getId()).orElseThrow();
        String ownerPath = "drive-profile/" + owner.getId() + "/profile.png";
        ownerSettings.setProfileImagePath(ownerPath); ownerSettings.setProfileImageBucket("profile-bucket");
        driveProfileSettings.saveAndFlush(ownerSettings);
        var otherSettings = driveProfileSettings.findByUser_Id(other.getId()).orElseThrow();
        String otherPath = "drive-profile/" + other.getId() + "/profile.png";
        otherSettings.setProfileImagePath(otherPath); driveProfileSettings.saveAndFlush(otherSettings);

        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        assertThat(driveProfileSettings.findByUser_Id(owner.getId())).isEmpty();
        assertThat(driveProfileSettings.findByUser_Id(other.getId()).orElseThrow().getProfileImagePath()).isEqualTo(otherPath);
        assertThat(tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())))
                .singleElement().satisfies(task -> {
                    assertThat(task.getKind()).isEqualTo(WithdrawalCleanupKind.DRIVE_PROFILE_INVENTORY);
                    assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
                    assertThat(task.getEncryptedPayload()).doesNotContain(ownerPath);
                    try {
                        var payload = json.readTree(cipher.decrypt(requestId, UUID.fromString(task.getId()), task.getKind().name(), task.getEncryptedPayload()));
                        assertThat(payload.path("storagePath").asText()).isEqualTo(ownerPath);
                        assertThat(payload.path("storageBucket").asText()).isEqualTo("profile-bucket");
                    } catch (Exception failure) { throw new AssertionError(failure); }
                });
    }

    @Test void invalidProfileImagePathRollsBackTheEntireWithdrawal() {
        AppUser owner = user();
        driveProfileService.getSettings(owner.getId());
        DriveProfileSettings settings = driveProfileSettings.findByUser_Id(owner.getId()).orElseThrow();
        settings.setProfileImagePath("drive-profile/another-user/profile.png");
        driveProfileSettings.saveAndFlush(settings);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("프로필 사진 경로");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(driveProfileSettings.findByUser_Id(owner.getId())).isPresent();
    }

    @Test void rolledBackWithdrawalRestoresProfileSettingsAndDiscardsInventory() {
        AppUser owner = user();
        driveProfileService.getSettings(owner.getId());
        var settings = driveProfileSettings.findByUser_Id(owner.getId()).orElseThrow();
        String path = "drive-profile/" + owner.getId() + "/profile.png";
        settings.setProfileImagePath(path); driveProfileSettings.saveAndFlush(settings);
        final UUID[] requestId = new UUID[1];
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            requestId[0] = start.start(owner.getId(), "12345678", Map.of(), Map.of());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("force rollback");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(driveProfileSettings.findByUser_Id(owner.getId()).orElseThrow().getProfileImagePath()).isEqualTo(path);
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId[0].toString()))).isEmpty();
    }

    @Test void profileOperationsWaitingForWithdrawalCannotRecreateSettingsOrUpload() throws Exception {
        AppUser owner = user();
        driveProfileService.getSettings(owner.getId());
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(4);
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png);
        var image = new MockMultipartFile("image", "profile.png", "image/png", png.toByteArray());
        try {
            var withdrawal = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
                started.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("withdrawal timed out"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                return requestId;
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var read = pool.submit(() -> driveProfileService.getSettings(owner.getId()));
            var update = pool.submit(() -> driveProfileService.updateSettings(owner.getId(),
                    new DriveDtos.ProfileSettingsUpdateRequest("new name", null, null, null)));
            var upload = pool.submit(() -> driveProfileService.uploadProfileImage(owner.getId(), image));
            assertThatThrownBy(() -> read.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThatThrownBy(() -> update.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThatThrownBy(() -> upload.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); withdrawal.get(10, TimeUnit.SECONDS);
            for (var result : List.of(read, update, upload)) {
                assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(NotFoundException.class);
            }
            assertThat(driveProfileSettings.findByUser_Id(owner.getId())).isEmpty();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void staleClusterCalculationWaitingForWithdrawalCannotRecreateDerivedData() throws Exception {
        AppUser departing = user(), other = user();
        ClusterFixture own = cluster(departing), retained = cluster(other);
        var point = new PhotoPoint(own.clusterId(), own.clusterId(), own.clusterId(), "테스트 여행", "#3182F6",
                LocalDate.now(), LocalTime.NOON, "장소", "방문", null, null, null,
                new BigDecimal("37.5"), new BigDecimal("127"), LocalDateTime.now(), false);
        var staleCalculation = new TravelPhotoClusterPolicy().cluster(List.of(point));
        kakao(departing, "423456789");
        when(kakao.isUnlinkConfigured()).thenReturn(true);
        var cleanupFinished = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            cleanupFinished.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("withdrawal timed out");
            return invocation.callRealMethod();
        }).when(cipher).encrypt(any(), any(), eq(WithdrawalCleanupKind.KAKAO_UNLINK.name()), anyString());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var withdrawal = pool.submit(() -> start.start(departing.getId(), "12345678", Map.of(), Map.of()));
            assertThat(cleanupFinished.await(5, TimeUnit.SECONDS)).isTrue();
            var oldCalculation = pool.submit(() -> clusterProjection.apply(departing.getId(), staleCalculation));
            assertThatThrownBy(() -> oldCalculation.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown(); withdrawal.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> oldCalculation.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(NotFoundException.class);
            assertThat(jdbc.queryForObject("select count(*) from travel_photo_clusters where id = ?", Integer.class, own.clusterId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from travel_photo_clusters where id = ?", Integer.class, retained.clusterId())).isEqualTo(1);
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void requestScopedOldUserCannotWriteClustersAfterWithdrawal() throws Exception {
        AppUser departing = user();
        var requestEntityManager = entityManagerFactory.createEntityManager();
        var pool = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestEntityManager));
        try {
            AppUser stale = requestEntityManager.find(AppUser.class, departing.getId());
            assertThat(stale.isActive()).isTrue();
            pool.submit(() -> start.start(departing.getId(), "12345678", Map.of(), Map.of()))
                    .get(10, TimeUnit.SECONDS);
            assertThat(stale.isActive()).isTrue();
            assertThatThrownBy(() -> clusterProjection.apply(departing.getId(), List.of()))
                    .isInstanceOf(NotFoundException.class);
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestEntityManager.close(); pool.shutdownNow();
        }
    }

    @Test void queuedClusterCalculationCannotPublishAfterWithdrawalCommits() throws Exception {
        AppUser departing = user();
        var calculating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var refused = new CountDownLatch(1);
        doAnswer(invocation -> {
            calculating.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("cluster timed out");
            return invocation.callRealMethod();
        }).when(clusterProjection).compute(departing.getId());
        var fenceTarget = org.springframework.test.util.AopTestUtils
                .<TravelClusterWithdrawalFence>getUltimateTargetObject(clusterFence);
        doAnswer(invocation -> {
            boolean available = (boolean) invocation.callRealMethod();
            if (!available) refused.countDown();
            return available;
        }).when(fenceTarget).lockAvailableOwner(departing.getId());
        try {
            clusterCoordinator.markDirty(departing.getId());
            assertThat(calculating.await(5, TimeUnit.SECONDS)).isTrue();
            start.start(departing.getId(), "12345678", Map.of(), Map.of());
            release.countDown();
            assertThat(refused.await(10, TimeUnit.SECONDS)).isTrue();
            verify(clusterProjection, never()).apply(eq(departing.getId()), any());
            assertThat(jdbc.queryForObject("select count(*) from travel_photo_cluster_refresh_jobs where owner_id = ?", Integer.class,
                    departing.getId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from travel_photo_clusters where owner_id = ?", Integer.class,
                    departing.getId())).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test void directShareCleanupPreservesFilesAndSharesBetweenOtherUsers() {
        AppUser departing = user(), owner = user(), other = user();
        DriveShareFixture shared = directShare(owner, departing, other);
        DriveShareFixture onlyDeparting = directShare(owner, departing, null);
        DriveShareFixture ownedByDeparting = directShare(departing, other, null);

        start.start(departing.getId(), "12345678", Map.of(), Map.of());

        assertThat(driveShares.findById(shared.departingShare())).isEmpty();
        assertThat(driveShares.findById(shared.otherShare())).isPresent();
        assertThat(driveItems.findById(shared.item()).orElseThrow().isSharedFile()).isTrue();
        assertThat(driveShares.findById(onlyDeparting.departingShare())).isEmpty();
        assertThat(driveItems.findById(onlyDeparting.item()).orElseThrow().isSharedFile()).isFalse();
        assertThat(driveShares.findById(ownedByDeparting.departingShare())).isEmpty();
        assertThat(driveItems.findById(ownedByDeparting.item())).isEmpty();
    }

    @Test void directShareCleanupProcessesMoreThanOnePageWithoutDeletingFiles() {
        AppUser departing = user(), owner = user();
        List<Long> itemIds = new TransactionTemplate(transactions).execute(status -> {
            AppUser source = entities.getReference(AppUser.class, owner.getId());
            AppUser recipient = entities.getReference(AppUser.class, departing.getId());
            var ids = new java.util.ArrayList<Long>();
            for (int i = 0; i < 105; i++) {
                DriveItem item = new DriveItem(); item.setOwner(source); item.setOriginalName("photo-" + i + ".txt");
                item.setStoredName(UUID.randomUUID() + ".txt"); item.setSharedFile(true); entities.persist(item);
                DriveShare share = new DriveShare(); share.setOwner(source); share.setRecipient(recipient);
                share.setItem(item); entities.persist(share); ids.add(item.getId());
            }
            entities.flush();
            return ids;
        });
        start.start(departing.getId(), "12345678", Map.of(), Map.of());
        assertThat(driveShares.findAllByRecipient_IdOrderByCreatedAtDesc(departing.getId())).isEmpty();
        assertThat(driveItems.findAllById(itemIds)).hasSize(105).allMatch(item -> !item.isSharedFile());
    }

    @Test void inconsistentDirectShareOwnerStopsWithdrawalWithoutDeletingEitherUsersData() {
        AppUser departing = user(), actualOwner = user(), recipient = user();
        DriveShareFixture corrupt = directShare(actualOwner, recipient, null);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            DriveShare share = entities.find(DriveShare.class, corrupt.departingShare());
            share.setOwner(entities.getReference(AppUser.class, departing.getId()));
        });

        assertThatThrownBy(() -> start.start(departing.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("소유자");
        assertThat(users.findById(departing.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(departing.getId())).isEmpty();
        assertThat(driveItems.findById(corrupt.item())).isPresent();
        assertThat(driveShares.findById(corrupt.departingShare())).isPresent();
    }

    @Test void directSharesCannotBeRemovedForAnActiveOrMerelyDisabledAccount() {
        AppUser owner = user(), active = user(), disabled = user();
        DriveShareFixture activeShare = directShare(owner, active, null);
        DriveShareFixture disabledShare = directShare(owner, disabled, null);
        AppUser disabledUser = users.findById(disabled.getId()).orElseThrow();
        disabledUser.setActive(false); users.saveAndFlush(disabledUser);
        for (Long id : List.of(active.getId(), disabled.getId())) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                    driveShareCleanup.removeDirectShares(id, UUID.randomUUID())))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("탈퇴 처리 중");
        }
        assertThat(driveShares.findById(activeShare.departingShare())).isPresent();
        assertThat(driveShares.findById(disabledShare.departingShare())).isPresent();
    }

    @Test void incorrectPinOrMissingKakaoConfigurationCannotDeactivateAnAccount() {
        AppUser owner = user();
        assertThatThrownBy(() -> start.start(owner.getId(), "87654321", Map.of(), Map.of()))
                .isInstanceOf(BadCredentialsException.class);
        kakao(owner, "223456789");
        when(kakao.isUnlinkConfigured()).thenReturn(false);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
    }

    @Test void unknownKakaoAppCannotDeactivateAnAccountOrQueueAnUnlink() {
        AppUser owner = user(); SocialAccount linked = kakao(owner, Long.toString(8_000_000_000L + owner.getId()));
        linked.setProviderAppId(null); social.saveAndFlush(linked);
        when(kakao.isUnlinkConfigured()).thenReturn(true);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
    }

    @Test void kakaoPreflightRequiresTheOriginalInactiveAccountBindingAndAnUnexpiredLease() {
        AppUser owner = user(); String subject = Long.toString(900_000_000L + owner.getId());
        SocialAccount linked = kakao(owner, subject);
        when(kakao.isUnlinkConfigured()).thenReturn(true);
        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        WithdrawalCleanupTask task = tasks.findAll().stream()
                .filter(value -> value.getRequestId().equals(requestId.toString())
                        && value.getKind() == WithdrawalCleanupKind.KAKAO_UNLINK).findFirst().orElseThrow();
        UUID taskId = UUID.fromString(task.getId()), claimToken = UUID.randomUUID();
        LocalDateTime lease = LocalDateTime.now().plusMinutes(5);
        task.setState(WithdrawalCleanupTask.State.PROCESSING); task.setClaimToken(claimToken.toString());
        task.setLeaseUntil(lease); task.setAttempts(1); tasks.saveAndFlush(task);
        var claim = new WithdrawalCleanupTaskStore.Claim(requestId, taskId, WithdrawalCleanupKind.KAKAO_UNLINK,
                claimToken, lease, 1, subject);
        var minimumTime = java.time.Duration.ofMinutes(3).plusSeconds(10);

        assertThat(kakaoPreflight.verify(claim, minimumTime)).isEqualTo(KakaoWithdrawalPreflight.Result.READY);
        var altered = new WithdrawalCleanupTaskStore.Claim(requestId, taskId, WithdrawalCleanupKind.KAKAO_UNLINK,
                claimToken, lease, 1, "12345");
        assertThat(kakaoPreflight.verify(altered, minimumTime)).isEqualTo(KakaoWithdrawalPreflight.Result.MANUAL_REVIEW);
        var stale = new WithdrawalCleanupTaskStore.Claim(requestId, taskId, WithdrawalCleanupKind.KAKAO_UNLINK,
                UUID.randomUUID(), lease, 1, subject);
        assertThat(kakaoPreflight.verify(stale, minimumTime)).isEqualTo(KakaoWithdrawalPreflight.Result.LOST_LEASE);

        task = tasks.findById(task.getId()).orElseThrow(); task.setLeaseUntil(LocalDateTime.now().plusMinutes(1)); tasks.saveAndFlush(task);
        assertThat(kakaoPreflight.verify(claim, minimumTime)).isEqualTo(KakaoWithdrawalPreflight.Result.LOST_LEASE);
        task = tasks.findById(task.getId()).orElseThrow(); task.setLeaseUntil(LocalDateTime.now().plusMinutes(5)); tasks.saveAndFlush(task);
        AppUser refreshed = users.findById(owner.getId()).orElseThrow(); refreshed.setActive(true); users.saveAndFlush(refreshed);
        assertThat(kakaoPreflight.verify(claim, minimumTime)).isEqualTo(KakaoWithdrawalPreflight.Result.MANUAL_REVIEW);
        refreshed.setActive(false); users.saveAndFlush(refreshed);
        linked.setProviderUserId(Long.toString(1_000_000_000L + owner.getId())); social.saveAndFlush(linked);
        assertThat(kakaoPreflight.verify(claim, minimumTime)).isEqualTo(KakaoWithdrawalPreflight.Result.MANUAL_REVIEW);
    }

    @Test void crossOwnerCatalogReferencesStopWithdrawalWithoutChangingEitherUser() {
        AppUser owner = user(), other = user();
        long entryId = new TransactionTemplate(transactions).execute(status -> {
            var entry = new LedgerEntry();
            entry.setOwner(users.findById(other.getId()).orElseThrow());
            entry.setCategoryGroup(ledgerCategories.findAllByOwnerIdAndEntryTypeOrderByDisplayOrderAscIdAsc(
                    owner.getId(), EntryType.EXPENSE).get(0));
            entry.setPaymentMethod(payments.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(other.getId()).get(0));
            entry.setEntryDate(LocalDate.of(2034, 7, 19));
            entry.setTitle("다른 사람 기록");
            entry.setAmount(BigDecimal.valueOf(1000));
            entry.setEntryType(EntryType.EXPENSE);
            return ledgerEntries.saveAndFlush(entry).getId();
        });
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사용자");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(ledgerEntries.findById(entryId)).isPresent();
        assertThat(ledgerCategories.findAllByOwnerIdOrderByDisplayOrderAscIdAsc(owner.getId())).isNotEmpty();
    }

    @Test void supportCleanupQueuesExactAttachmentAndPreservesOtherUsersInquiry() {
        AppUser owner = user(), other = user();
        String path = "user-" + owner.getId() + "/" + UUID.randomUUID() + ".png";
        long own = inquiry(owner, null, path);
        long ownDuplicate = inquiry(owner, null, path);
        long otherInquiry = inquiry(other, owner, null);
        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());

        assertThat(inquiries.findById(own)).isEmpty();
        assertThat(inquiries.findById(ownDuplicate)).isEmpty();
        var retained = inquiries.findById(otherInquiry).orElseThrow();
        assertThat(retained.getRepliedBy()).isNull();
        assertThat(retained.getReplyContent()).isEqualTo("보존할 답변");
        var queued = tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())).toList();
        assertThat(queued).singleElement().satisfies(task -> {
            assertThat(task.getKind()).isEqualTo(WithdrawalCleanupKind.SUPPORT_ATTACHMENT);
            assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
            assertThat(task.getEncryptedPayload()).doesNotContain(path);
            assertThat(cipher.decrypt(requestId, UUID.fromString(task.getId()), task.getKind().name(), task.getEncryptedPayload()))
                    .isEqualTo(path);
        });
    }

    @Test void supportCleanupIncludesNewAttachmentProvenanceInItsEncryptedTask() {
        AppUser owner = user();
        String path = "user-" + owner.getId() + "/" + UUID.randomUUID() + ".png";
        long id = inquiry(owner, null, path);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var inquiry = inquiries.findById(id).orElseThrow();
            inquiry.setAttachmentStorageId("test_storage_identifier_001");
            inquiry.setAttachmentSha256("a".repeat(64));
        });
        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        assertThat(tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())
                && task.getKind() == WithdrawalCleanupKind.SUPPORT_ATTACHMENT)).singleElement().satisfies(task -> {
            try {
                var payload = json.readValue(cipher.decrypt(requestId, UUID.fromString(task.getId()), task.getKind().name(),
                        task.getEncryptedPayload()), SupportAttachmentTarget.class);
                assertThat(payload.ownerId()).isEqualTo(owner.getId());
                assertThat(payload.path()).isEqualTo(path);
                assertThat(payload.storageId()).isEqualTo("test_storage_identifier_001");
                assertThat(payload.sha256()).isEqualTo("a".repeat(64));
                assertThat(task.getEncryptedPayload()).doesNotContain(path, payload.storageId());
            } catch (Exception exception) { throw new AssertionError(exception); }
        });
    }

    @Test void unsafeOrCrossOwnerSupportAttachmentStopsWithdrawalWithoutDeletingInquiries() {
        AppUser owner = user(), other = user();
        String path = "user-" + owner.getId() + "/" + UUID.randomUUID() + ".jpg";
        long own = inquiry(owner, null, path);
        long crossOwner = inquiry(other, null, path);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사용자");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(inquiries.findById(own)).isPresent();
        assertThat(inquiries.findById(crossOwner)).isPresent();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();

        AppUser malformedOwner = user();
        long malformed = inquiry(malformedOwner, null, "user-" + malformedOwner.getId() + "/../outside.png");
        assertThatThrownBy(() -> start.start(malformedOwner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("경로");
        assertThat(inquiries.findById(malformed)).isPresent();
        assertThat(users.findById(malformedOwner.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test void recordMapCleanupQueuesPhotoAndPreservesOtherOwnersMap() throws Exception {
        AppUser owner = user(), other = user();
        MapFixture own = mapFixture(owner), kept = mapFixture(other);
        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        for (String table : List.of("record_map_places", "record_map_collections", "record_map_visits", "record_map_photos", "record_map_shares")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where owner_id = ?", Integer.class, owner.getId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + table + " where owner_id = ?", Integer.class, other.getId())).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from record_map_collection_places where place_id = ?", Integer.class, own.place())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from record_map_collection_places where place_id = ?", Integer.class, kept.place())).isEqualTo(1);
        var queued = tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())).toList();
        assertThat(queued).singleElement().satisfies(task -> {
            assertThat(task.getKind()).isEqualTo(WithdrawalCleanupKind.RECORD_MAP_MEDIA);
            assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
            assertThat(task.getEncryptedPayload()).doesNotContain(own.path());
            try {
                var payload = json.readTree(cipher.decrypt(requestId, UUID.fromString(task.getId()), task.getKind().name(), task.getEncryptedPayload()));
                assertThat(payload.path("ownerId").asLong()).isEqualTo(owner.getId());
                assertThat(payload.path("visitId").asLong()).isEqualTo(own.visit());
                assertThat(payload.path("path").asText()).isEqualTo(own.path());
            } catch (Exception exception) { throw new AssertionError(exception); }
        });
    }

    @Test void anotherUsersVisitOnOwnedPlaceStopsRecordMapCleanup() {
        AppUser owner = user(), other = user();
        MapFixture own = mapFixture(owner);
        long crossVisit = new TransactionTemplate(transactions).execute(status -> {
            var visit = new PlaceVisit();
            visit.setOwnerId(other.getId());
            visit.setPlace(entities.find(RecordPlace.class, own.place()));
            visit.setTitle("다른 사용자 방문"); visit.setVisitDate(LocalDate.now());
            entities.persist(visit); entities.flush(); return visit.getId();
        });
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사용자");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from record_map_visits where id = ?", Integer.class, crossVisit)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from record_map_places where id = ?", Integer.class, own.place())).isEqualTo(1);
    }

    @Test void anotherUsersPhotoOnOwnedVisitStopsRecordMapCleanup() {
        AppUser owner = user(), other = user();
        MapFixture own = mapFixture(owner);
        long crossPhoto = new TransactionTemplate(transactions).execute(status -> {
            var photo = new PlacePhoto();
            photo.setOwnerId(other.getId());
            photo.setVisit(entities.find(PlaceVisit.class, own.visit()));
            photo.setOriginalName("other.png"); photo.setStoragePath("other.png");
            photo.setContentType("image/png"); photo.setFileSize(1);
            entities.persist(photo); entities.flush(); return photo.getId();
        });
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사용자");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from record_map_photos where id = ?", Integer.class, crossPhoto)).isEqualTo(1);
    }

    @Test void crossOwnerCollectionMembershipStopsRecordMapCleanupInEitherDirection() {
        for (boolean ownerPlace : List.of(true, false)) {
            AppUser owner = user(), other = user();
            MapFixture own = mapFixture(owner), kept = mapFixture(other);
            long placeId = ownerPlace ? own.place() : kept.place();
            long collectionId = ownerPlace ? kept.collection() : own.collection();
            new TransactionTemplate(transactions).executeWithoutResult(status ->
                    entities.find(RecordPlace.class, placeId).getCollections().add(entities.find(PlaceCollection.class, collectionId)));
            assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 사용자");
            assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
            assertThat(requests.findByUserId(owner.getId())).isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from record_map_collection_places where place_id = ? and collection_id = ?",
                    Integer.class, placeId, collectionId)).isEqualTo(1);
        }
    }

    @Test void unsafeRecordMapPhotoPathRollsBackTheEntireWithdrawal() {
        AppUser owner = user();
        MapFixture own = mapFixture(owner);
        var workbook = excelWriter.create(owner.getId(), "{\"private\":true}");
        var analysis = aiWriter.create(owner.getId(), aiDraft());
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                entities.find(PlacePhoto.class, own.photo()).setStoragePath("travel-media/" + owner.getId() + "/0/" + own.visit() + "/../other.png"));
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("경로");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
        assertThat(excelJobs.findById(workbook.getId())).isPresent();
        assertThat(aiHistories.findById(analysis.getId())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from record_map_photos where id = ?", Integer.class, own.photo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select active from record_map_shares where id = ?", Boolean.class, own.share())).isTrue();
    }

    @Test void withdrawalRemovesOnlyOwnersWorkbookJobsAndLateCompletionCannotRestoreThem() {
        AppUser owner = user(), other = user();
        var own = excelWriter.create(owner.getId(), "{\"private\":true}");
        var kept = excelWriter.create(other.getId(), "{\"other\":true}");
        start.start(owner.getId(), "12345678", Map.of(), Map.of());
        assertThat(excelJobs.findById(own.getId())).isEmpty();
        assertThat(excelJobs.findById(kept.getId())).isPresent();
        assertThat(excelJobs.completeProcessing(own.getId(), owner.getId(), own.getVersion(), "{\"late\":true}")).isZero();
        assertThat(excelJobs.findById(own.getId())).isEmpty();
    }

    @Test void withdrawalRemovesOnlyOwnersAiHistoryAndLateCompletionCannotRestoreIt() {
        AppUser owner = user(), other = user();
        var own = aiWriter.create(owner.getId(), aiDraft());
        var kept = aiWriter.create(other.getId(), aiDraft());
        start.start(owner.getId(), "12345678", Map.of(), Map.of());
        assertThat(aiHistories.findById(own.getId())).isEmpty();
        assertThat(aiHistories.findById(kept.getId())).isPresent();
        assertThat(aiHistories.completeProcessing(own.getId(), owner.getId(), "late", "{\"late\":true}")).isZero();
        assertThat(aiHistories.findById(own.getId())).isEmpty();
    }

    @Test void withdrawalQueuesOnlyOwnersImageOriginalAndRemovesItsHistory() throws Exception {
        AppUser owner = user(), other = user();
        ImageFixture own = imageFixture(owner), kept = imageFixture(other);
        imageHistories.attachStoredImageWithBucket(own.id(), owner.getId(), own.key(), "original-bucket",
                "fixture_storage_id_1234", "fixture-version", "fixture-etag", LocalDateTime.now());
        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        assertThat(imageHistories.findById(own.id())).isEmpty();
        assertThat(imageHistories.findById(kept.id())).isPresent();
        assertThat(imageHistories.completeAttempt(own.id(), owner.getId(), own.version(), "AUTO", "late", "late", "{}", LocalDateTime.now())).isZero();
        var queued = tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())).toList();
        assertThat(queued).singleElement().satisfies(task -> {
            assertThat(task.getKind()).isEqualTo(WithdrawalCleanupKind.LEDGER_IMAGE);
            assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
            assertThat(task.getEncryptedPayload()).doesNotContain(own.key());
            try {
                var payload = json.readTree(cipher.decrypt(requestId, UUID.fromString(task.getId()), task.getKind().name(), task.getEncryptedPayload()));
                assertThat(payload.path("ownerId").asLong()).isEqualTo(owner.getId());
                assertThat(payload.path("analysisId").asLong()).isEqualTo(own.id());
                assertThat(payload.path("objectKey").asText()).isEqualTo(own.key());
                assertThat(payload.path("bucket").asText()).isEqualTo("original-bucket");
                assertThat(payload.path("storageId").asText()).isEqualTo("fixture_storage_id_1234");
                assertThat(payload.path("versionId").asText()).isEqualTo("fixture-version");
                assertThat(payload.path("etag").asText()).isEqualTo("fixture-etag");
            } catch (Exception exception) { throw new AssertionError(exception); }
        });
    }

    @Test void withdrawalDoesNotGuessTheBucketForLegacyImageOriginals() throws Exception {
        AppUser owner = user(); ImageFixture original = imageFixture(owner);
        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        var task = tasks.findAll().stream().filter(value -> value.getRequestId().equals(requestId.toString())
                && value.getKind() == WithdrawalCleanupKind.LEDGER_IMAGE).findFirst().orElseThrow();
        var payload = json.readTree(cipher.decrypt(requestId, UUID.fromString(task.getId()), task.getKind().name(), task.getEncryptedPayload()));
        assertThat(payload.path("objectKey").asText()).isEqualTo(original.key());
        assertThat(payload.path("bucket").isNull()).isTrue();
        assertThat(payload.path("etag").isNull()).isTrue();
    }

    @Test void unsafeImageOriginalOrAnotherOwnersReferenceRollsBackWithdrawal() {
        AppUser unsafeOwner = user();
        ImageFixture unsafe = imageFixture(unsafeOwner);
        imageHistories.attachStoredImage(unsafe.id(), unsafeOwner.getId(), "ledger-image-analysis/../other.jpg", LocalDateTime.now());
        assertThatThrownBy(() -> start.start(unsafeOwner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("경로");
        assertThat(users.findById(unsafeOwner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(imageHistories.findById(unsafe.id())).isPresent();
        assertThat(requests.findByUserId(unsafeOwner.getId())).isEmpty();

        AppUser owner = user(), other = user();
        ImageFixture own = imageFixture(owner), reference = imageFixture(other);
        imageHistories.attachStoredImage(reference.id(), other.getId(), own.key(), LocalDateTime.now());
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 자료");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(imageHistories.findById(own.id())).isPresent();
        assertThat(imageHistories.findById(reference.id())).isPresent();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
    }

    @Test void driveReferenceToImageOriginalStopsWithdrawal() {
        AppUser owner = user(), other = user();
        ImageFixture own = imageFixture(owner);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var item = new DriveItem(); item.setOwner(users.findById(other.getId()).orElseThrow());
            item.setOriginalName("보존.jpg"); item.setStoredName("photo.jpg"); item.setExtension("jpg");
            item.setStoragePath(own.key()); entities.persist(item);
        });
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 자료");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(imageHistories.findById(own.id())).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from drive_items where storage_path = ?", Integer.class, own.key())).isEqualTo(1);
    }

    @Test void withdrawalRemovesImageHistoryWithoutAStoredOriginal() {
        AppUser owner = user();
        var image = imageWriter.create(owner.getId(), new LedgerImageAnalysisRequest());
        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        assertThat(imageHistories.findById(image.getId())).isEmpty();
        assertThat(tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString()))).isEmpty();
    }

    @Test void withdrawalWaitsForImageReferenceAndQueuesTheCommittedOriginal() throws Exception {
        AppUser owner = user();
        var image = imageWriter.create(owner.getId(), new LedgerImageAnalysisRequest());
        String key = "ledger-image-analysis/" + owner.getId() + "/" + image.getId() + "/" + UUID.randomUUID() + ".jpg";
        var pool = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var attachment = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                users.lockForAccountSecurityUpdate(owner.getId()).orElseThrow();
                imageHistories.attachStoredImage(image.getId(), owner.getId(), key, LocalDateTime.now());
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("attachment timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var withdrawal = pool.submit(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()));
            assertThatThrownBy(() -> withdrawal.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            attachment.get(5, TimeUnit.SECONDS);
            UUID requestId = withdrawal.get(10, TimeUnit.SECONDS);
            assertThat(imageHistories.findById(image.getId())).isEmpty();
            assertThat(tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())))
                    .singleElement().satisfies(task -> assertThat(task.getKind()).isEqualTo(WithdrawalCleanupKind.LEDGER_IMAGE));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void imageReferenceCannotAttachAfterWithdrawalCommits() throws Exception {
        AppUser owner = user();
        var image = imageWriter.create(owner.getId(), new LedgerImageAnalysisRequest());
        String key = "ledger-image-analysis/" + owner.getId() + "/" + image.getId() + "/" + UUID.randomUUID() + ".jpg";
        var pool = Executors.newFixedThreadPool(2);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var withdrawal = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("withdrawal timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return requestId;
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var attach = pool.submit(() -> imageReferences.attach(owner.getId(), image.getId(), new StoredImage(key, LocalDateTime.now())));
            assertThatThrownBy(() -> attach.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            withdrawal.get(10, TimeUnit.SECONDS);
            assertThat(attach.get(5, TimeUnit.SECONDS)).isZero();
            assertThat(imageHistories.findById(image.getId())).isEmpty();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void aDriveReferenceToRecordMapPhotoStopsDeletionInsteadOfRiskingSharedData() {
        AppUser owner = user(), other = user();
        MapFixture own = mapFixture(owner);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var item = new DriveItem(); item.setOwner(users.findById(other.getId()).orElseThrow());
            item.setOriginalName("보존.png"); item.setStoredName("photo.png"); item.setExtension("png");
            item.setStoragePath(own.path()); entities.persist(item);
        });
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 자료");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from record_map_photos where id = ?", Integer.class, own.photo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from drive_items where storage_path = ?", Integer.class, own.path())).isEqualTo(1);
    }

    @Test void lastActiveAdministratorCannotStartWithdrawal() {
        AppUser admin = appUsers.registerUser(unique(), "관리자", "test-password", "12345678", AppUserRole.ADMIN);
        assertThatThrownBy(() -> start.start(admin.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("마지막 관리자");
        assertThat(users.findById(admin.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test void withdrawalRejectsAnExistingRepeatableReadTransactionWithoutChangingData() {
        AppUser owner = user();
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                start.start(owner.getId(), "12345678", Map.of(), Map.of())))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("격리 수준");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();
    }

    @Test void withdrawalWaitsForAnInFlightSharedAlbumAndPreservesItsPrivateFlaggedPhoto() throws Exception {
        AppUser owner = user(), member = user();
        FamilyCategory category = family(owner, member);
        FamilyMediaAsset photo = familyPhoto(category, owner);
        photo.setShared(false); familyMedia.saveAndFlush(photo);
        String path = photo.getStoragePath();
        var pool = Executors.newFixedThreadPool(2);
        var created = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var album = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var result = familyService.createAlbum(member.getId(),
                        new FamilyAlbumCreateRequest(category.getId(), "함께 보는 앨범", null, List.of(photo.getId())));
                created.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("album timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(exception);
                }
                return result.id();
            }));
            assertThat(created.await(5, TimeUnit.SECONDS)).isTrue();
            var withdrawal = pool.submit(() -> start.start(owner.getId(), "12345678", Map.of(category.getId(), member.getId()), Map.of()));
            assertThatThrownBy(() -> withdrawal.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            Long albumId = album.get(5, TimeUnit.SECONDS);
            withdrawal.get(10, TimeUnit.SECONDS);
            assertThat(familyAlbums.findById(albumId)).isPresent();
            var kept = familyMedia.findById(photo.getId()).orElseThrow();
            assertThat(kept.getOwner().getId()).isEqualTo(member.getId());
            assertThat(kept.getStoragePath()).isEqualTo(path);
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test void aNewAlbumCannotReferenceAWithdrawingUsersUntransferredPrivatePhoto() {
        AppUser owner = user(), member = user();
        FamilyCategory category = family(owner, member);
        FamilyMediaAsset photo = familyPhoto(category, owner);
        photo.setShared(false); familyMedia.saveAndFlush(photo);
        start.start(owner.getId(), "12345678", Map.of(category.getId(), member.getId()), Map.of());
        assertThatThrownBy(() -> familyService.createAlbum(member.getId(),
                new FamilyAlbumCreateRequest(category.getId(), "새 앨범", null, List.of(photo.getId()))))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("파일");
        assertThat(familyMedia.findById(photo.getId())).isEmpty();
        assertThat(familyAlbums.findAllByCategoryIdInOrderByCreatedAtDescIdDesc(List.of(category.getId()))).isEmpty();
    }

    @Test void withdrawalQueuesPrivateFamilyMediaAndPreservesOtherUsersCategory() throws Exception {
        AppUser owner = user(), other = user();
        FamilyCategory ownCategory = family(owner, owner), otherCategory = family(other, owner);
        FamilyMediaAsset ownPhoto = familyPhoto(ownCategory, owner), otherPhoto = familyPhoto(otherCategory, other);
        var album = familyService.createAlbum(owner.getId(),
                new FamilyAlbumCreateRequest(ownCategory.getId(), "개인 앨범", null, List.of(ownPhoto.getId())));
        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        assertThat(familyCategories.findById(ownCategory.getId())).isEmpty();
        assertThat(familyAlbums.findById(album.id())).isEmpty();
        assertThat(familyMedia.findById(ownPhoto.getId())).isEmpty();
        assertThat(familyCategories.findById(otherCategory.getId())).isPresent();
        assertThat(familyMedia.findById(otherPhoto.getId())).isPresent();
        assertThat(familyMembers.existsByCategoryIdAndUserId(otherCategory.getId(), owner.getId())).isFalse();
        var queued = tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())).toList();
        assertThat(queued).singleElement().satisfies(task -> {
            assertThat(task.getKind()).isEqualTo(WithdrawalCleanupKind.FAMILY_MEDIA);
            assertThat(task.getState()).isEqualTo(WithdrawalCleanupTask.State.PENDING);
            assertThat(task.getEncryptedPayload()).doesNotContain(ownPhoto.getStoragePath());
            try {
                var payload = json.readTree(cipher.decrypt(requestId, UUID.fromString(task.getId()), task.getKind().name(), task.getEncryptedPayload()));
                assertThat(payload.path("assetId").asLong()).isEqualTo(ownPhoto.getId());
                assertThat(payload.path("categoryId").asLong()).isEqualTo(ownCategory.getId());
                assertThat(payload.path("path").asText()).isEqualTo(ownPhoto.getStoragePath());
            } catch (Exception exception) { throw new AssertionError(exception); }
        });
    }

    @Test void familyMediaPreviouslyTransferredFromAnotherUploaderUsesItsOriginalPath() {
        AppUser owner = user(), earlierUploader = user();
        FamilyCategory category = family(owner, owner);
        FamilyMediaAsset photo = familyPhoto(category, owner);
        String originalPath = "family-media/" + earlierUploader.getId() + "/" + category.getId() + "/" + photo.getStoredFileName();
        photo.setStoragePath(originalPath); familyMedia.saveAndFlush(photo);
        UUID requestId = start.start(owner.getId(), "12345678", Map.of(), Map.of());
        assertThat(familyMedia.findById(photo.getId())).isEmpty();
        assertThat(tasks.findAll().stream().filter(task -> task.getRequestId().equals(requestId.toString())))
                .singleElement().satisfies(task -> assertThat(task.getKind()).isEqualTo(WithdrawalCleanupKind.FAMILY_MEDIA));
    }

    @Test void sharedFamilyObjectOrUnsafePathRollsBackWithdrawal() {
        AppUser owner = user(), other = user();
        FamilyCategory ownCategory = family(owner, owner), otherCategory = family(other, other);
        FamilyMediaAsset ownPhoto = familyPhoto(ownCategory, owner), otherPhoto = familyPhoto(otherCategory, other);
        otherPhoto.setStoragePath(ownPhoto.getStoragePath()); familyMedia.saveAndFlush(otherPhoto);
        assertThatThrownBy(() -> start.start(owner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("다른 자료");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(familyMedia.findById(ownPhoto.getId())).isPresent();
        assertThat(familyMedia.findById(otherPhoto.getId())).isPresent();
        assertThat(requests.findByUserId(owner.getId())).isEmpty();

        AppUser unsafeOwner = user();
        FamilyCategory unsafeCategory = family(unsafeOwner, unsafeOwner);
        FamilyMediaAsset unsafePhoto = familyPhoto(unsafeCategory, unsafeOwner);
        unsafePhoto.setStoragePath("family-media/../outside.jpg"); familyMedia.saveAndFlush(unsafePhoto);
        assertThatThrownBy(() -> start.start(unsafeOwner.getId(), "12345678", Map.of(), Map.of()))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("경로");
        assertThat(users.findById(unsafeOwner.getId()).orElseThrow().isActive()).isTrue();
        assertThat(familyMedia.findById(unsafePhoto.getId())).isPresent();
        assertThat(requests.findByUserId(unsafeOwner.getId())).isEmpty();
    }

    @Test void administratorCannotReactivateAnAccountWhileWithdrawalIsInProgress() {
        AppUser owner = user();
        start.start(owner.getId(), "12345678", Map.of(), Map.of());
        assertThatThrownBy(() -> admins.updateUserActive(-1L, owner.getId(), true))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("탈퇴 처리 중");
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test void concurrentAdminReactivationCannotOvertakeAStartingWithdrawal() throws Exception {
        AppUser owner = user();
        var pool = Executors.newFixedThreadPool(2);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var withdrawal = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                start.start(owner.getId(), "12345678", Map.of(), Map.of());
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("withdrawal timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var reactivate = pool.submit(() -> admins.updateUserActive(-1L, owner.getId(), true));
            assertThatThrownBy(() -> reactivate.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            withdrawal.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> reactivate.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BadRequestException.class);
            assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void simultaneousRequestsForOneAccountCannotStartTwice() throws Exception {
        AppUser owner = user();
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { ready.countDown(); release.await(); return start.start(owner.getId(), "12345678", Map.of(), Map.of()); });
            var second = pool.submit(() -> { ready.countDown(); release.await(); return start.start(owner.getId(), "12345678", Map.of(), Map.of()); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            int success = 0, rejected = 0;
            for (var future : List.of(first, second)) {
                try { assertThat(future.get(15, TimeUnit.SECONDS)).isNotNull(); success++; }
                catch (ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(NotFoundException.class);
                    rejected++;
                }
            }
            assertThat(success).isEqualTo(1);
            assertThat(rejected).isEqualTo(1);
            assertThat(requests.findByUserId(owner.getId())).isPresent();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private AppUser user() { return appUsers.registerUser(unique(), "테스트 사용자", "test-password", "12345678"); }
    private void beginInternalRequest(AppUser owner, UUID requestId) {
        var request = new AccountWithdrawalRequest(); request.setId(requestId.toString()); request.setUserId(owner.getId());
        request.setState(AccountWithdrawalRequest.State.IN_PROGRESS); request.setRequestedAt(LocalDateTime.now());
        request.setUpdatedAt(LocalDateTime.now()); requests.saveAndFlush(request);
    }
    private TravelPlan travelPlan(AppUser owner) {
        TravelPlan plan = new TravelPlan(); plan.setOwner(owner); plan.setName("테스트 여행");
        plan.setStartDate(LocalDate.now()); plan.setEndDate(LocalDate.now()); plan.setStatus(TravelPlanStatus.COMPLETED);
        return travelPlans.saveAndFlush(plan);
    }
    private DriveDownloadLinkAccessLog driveAccessLog(AppUser owner, String status) {
        var log = new DriveDownloadLinkAccessLog(); log.setOwnerId(owner.getId()); log.setItemId(44L); log.setLinkId(55L);
        log.setStatus(status); log.setTokenFingerprint("test-fingerprint"); log.setClientAddress("192.0.2.1");
        log.setUserAgent("test-browser"); log.setAccessedAt(LocalDateTime.of(2026, 1, 2, 3, 4));
        return driveAccessLogRows.saveAndFlush(log);
    }
    private TravelMapLinkData.Draft mapLinkDraft(TravelPlan plan) {
        return new TravelMapLinkData.Draft("공유 지도", List.of(plan.getId()), List.of(), List.of(), List.of());
    }
    private TravelExpenseRecord travelRecord(TravelPlan plan) {
        var record = new TravelExpenseRecord(); record.setPlan(plan); record.setRecordType(TravelRecordType.MEMORY);
        record.setExpenseDate(LocalDate.now()); record.setCategory("장소"); record.setTitle("기록"); record.setAmount(BigDecimal.ZERO);
        return travelRecords.saveAndFlush(record);
    }
    private TravelMediaAsset travelAsset(TravelPlan plan, TravelExpenseRecord record, AppUser uploader, String file, String path) {
        var asset = new TravelMediaAsset(); asset.setPlan(plan); asset.setRecord(record); asset.setUploadedBy(uploader);
        asset.setOriginalFileName("test.jpg"); asset.setStoredFileName(file); asset.setStoragePath(path);
        asset.setContentType("image/jpeg"); asset.setFileSize(123L);
        return travelMedia.saveAndFlush(asset);
    }
    private TravelRouteSegment travelRoute(TravelPlan plan, String metadata) {
        var route = new TravelRouteSegment(); route.setPlan(plan); route.setRouteDate(LocalDate.now());
        route.setTitle("경로"); route.setRoutePathJson("[]"); route.setGpxFilesJson(metadata);
        return travelRoutes.saveAndFlush(route);
    }
    private ClusterFixture cluster(AppUser owner) {
        return new TransactionTemplate(transactions).execute(status -> {
            long id = 10_000_000L + owner.getId();
            var value = new TravelPhotoCluster(); value.setId(id); value.setOwnerId(owner.getId());
            value.setRepresentativeMediaId(id); value.setRepresentativeRecordId(id); value.setPlanId(id);
            value.setPlanName("테스트 여행"); value.setPlanColorHex("#3182F6");
            value.setLatitude(new BigDecimal("37.5")); value.setLongitude(new BigDecimal("127.0"));
            value.setPhotoCount(1); value.setMemoryCount(1); value.setMaxDistanceMeters(BigDecimal.ZERO);
            entities.persist(value);
            var member = new TravelPhotoClusterMember(); member.setOwnerId(owner.getId()); member.setClusterId(id);
            member.setMediaId(id); member.setSortOrder(0); entities.persist(member); entities.flush();
            jdbc.update("insert into travel_photo_cluster_refresh_jobs (owner_id, generation, processed_generation, dirty) values (?, 1, 1, false)", owner.getId());
            return new ClusterFixture(id, member.getId());
        });
    }
    private record ClusterFixture(Long clusterId, Long memberId) {}
    private DriveShareFixture directShare(AppUser owner, AppUser recipient, AppUser other) {
        return new TransactionTemplate(transactions).execute(status -> {
            DriveItem item = new DriveItem();
            item.setOwner(entities.getReference(AppUser.class, owner.getId()));
            item.setOriginalName("shared.txt"); item.setStoredName(UUID.randomUUID() + ".txt");
            item.setStoragePath("drive/" + owner.getId() + "/" + item.getStoredName());
            item.setSharedFile(true); entities.persist(item);
            DriveShare first = new DriveShare(); first.setItem(item); first.setOwner(item.getOwner());
            first.setRecipient(entities.getReference(AppUser.class, recipient.getId())); entities.persist(first);
            DriveShare second = null;
            if (other != null) {
                second = new DriveShare(); second.setItem(item); second.setOwner(item.getOwner());
                second.setRecipient(entities.getReference(AppUser.class, other.getId())); entities.persist(second);
            }
            entities.flush();
            return new DriveShareFixture(item.getId(), first.getId(), second == null ? null : second.getId());
        });
    }
    private record DriveShareFixture(Long item, Long departingShare, Long otherShare) {}
    private String unique() { return "wd" + UUID.randomUUID().toString().replace("-", ""); }
    private LedgerAiAnalysisHistory aiDraft() {
        var draft = new LedgerAiAnalysisHistory();
        draft.setMode(LedgerAiAnalysisMode.PERIOD); draft.setPeriodType(LedgerAiAnalysisPeriod.MONTH);
        draft.setStatus(LedgerAiAnalysisStatus.PROCESSING); draft.setFromDate(LocalDate.now().minusDays(1));
        draft.setToDate(LocalDate.now()); draft.setModel("test"); draft.setProvider("test"); draft.setTitle("private analysis");
        draft.setRequestPayloadJson("{\"private\":true}");
        return draft;
    }
    private ImageFixture imageFixture(AppUser owner) {
        var image = imageWriter.create(owner.getId(), new LedgerImageAnalysisRequest());
        String key = "ledger-image-analysis/" + owner.getId() + "/" + image.getId() + "/" + UUID.randomUUID() + ".jpg";
        imageHistories.attachStoredImage(image.getId(), owner.getId(), key, LocalDateTime.now());
        return new ImageFixture(image.getId(), imageHistories.findById(image.getId()).orElseThrow().getVersion(), key);
    }
    private record ImageFixture(Long id, Long version, String key) {}
    private MapFixture mapFixture(AppUser owner) {
        return new TransactionTemplate(transactions).execute(status -> {
            var collection = new PlaceCollection(); collection.setOwnerId(owner.getId()); collection.setName("장소 모음");
            entities.persist(collection);
            var place = new RecordPlace(); place.setOwnerId(owner.getId()); place.setName("장소");
            place.setLatitude(37.5); place.setLongitude(127.0); place.getCollections().add(collection); entities.persist(place);
            var visit = new PlaceVisit(); visit.setOwnerId(owner.getId()); visit.setPlace(place); visit.setTitle("방문");
            visit.setVisitDate(LocalDate.now()); entities.persist(visit); entities.flush();
            String path = "travel-media/" + owner.getId() + "/0/" + visit.getId() + "/" + UUID.randomUUID() + "-photo.png";
            var photo = new PlacePhoto(); photo.setOwnerId(owner.getId()); photo.setVisit(visit); photo.setOriginalName("photo.png");
            photo.setStoragePath(path); photo.setContentType("image/png"); photo.setFileSize(1); entities.persist(photo);
            var share = new RecordMapShare(); share.setOwnerId(owner.getId()); share.setCollection(collection);
            share.setTokenHash((UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""));
            share.setExpiresAt(LocalDateTime.now().plusDays(1)); entities.persist(share); entities.flush();
            return new MapFixture(collection.getId(), place.getId(), visit.getId(), photo.getId(), share.getId(), path);
        });
    }
    private record MapFixture(Long collection, Long place, Long visit, Long photo, Long share, String path) {}
    private long inquiry(AppUser sender, AppUser repliedBy, String path) {
        return new TransactionTemplate(transactions).execute(status -> {
            var inquiry = new SupportInquiry();
            inquiry.setSender(users.findById(sender.getId()).orElseThrow());
            inquiry.setTitle("문의 제목");
            inquiry.setContent("문의 본문");
            inquiry.setAttachmentStoragePath(path);
            if (repliedBy != null) {
                inquiry.setRepliedBy(users.findById(repliedBy.getId()).orElseThrow());
                inquiry.setReplyContent("보존할 답변");
            }
            return inquiries.saveAndFlush(inquiry).getId();
        });
    }
    private long classificationRule(AppUser owner) {
        return new TransactionTemplate(transactions).execute(status -> {
            AppUser managed = users.findById(owner.getId()).orElseThrow();
            CategoryGroup category = ledgerCategories.findAllByOwnerIdAndEntryTypeOrderByDisplayOrderAscIdAsc(
                    owner.getId(), EntryType.EXPENSE).get(0);
            var rule = new LedgerClassificationRule();
            rule.setOwner(managed);
            rule.setCategoryGroup(category);
            rule.setKeyword("개인 규칙");
            rule.setNormalizedKeyword("개인 규칙");
            return classificationRules.saveAndFlush(rule).getId();
        });
    }
    private long[] preferences(AppUser owner) {
        return new TransactionTemplate(transactions).execute(status -> {
            AppUser managed = users.findById(owner.getId()).orElseThrow();
            managed.setHouseholdAggregateSettingsJson("[1]");
            var layout = new UserLayoutSetting(); layout.setOwner(managed); layout.setLayoutScope("test-layout");
            layout.setPayloadJson("{}"); layouts.saveAndFlush(layout);
            var goal = new HouseholdGoal(); goal.setOwner(managed); goal.setTitle("개인 목표");
            goal.setTargetAmountKrw(BigDecimal.TEN); goal.setCurrentAmountKrw(BigDecimal.ONE); goal = goals.saveAndFlush(goal);
            return new long[]{layout.getId(), goal.getId()};
        });
    }
    private long[] ledger(AppUser owner) {
        return new TransactionTemplate(transactions).execute(status -> {
            AppUser managed = users.findById(owner.getId()).orElseThrow();
            var category = new CategoryGroup(); category.setOwner(managed); category.setName("식비");
            category.setEntryType(EntryType.EXPENSE); ledgerCategories.saveAndFlush(category);
            var payment = new PaymentMethod(); payment.setOwner(managed); payment.setName("현금");
            payment.setKind(PaymentMethodKind.CASH); payments.saveAndFlush(payment);
            var entry = new LedgerEntry(); entry.setOwner(managed); entry.setCategoryGroup(category); entry.setPaymentMethod(payment);
            entry.setEntryDate(LocalDate.of(2034, 7, 19)); entry.setTitle("거래"); entry.setMemo("개인 메모");
            entry.setAmount(BigDecimal.valueOf(1000)); entry.setEntryType(EntryType.EXPENSE); ledgerEntries.saveAndFlush(entry);
            var history = new LedgerEntryChangeHistory(); history.setOwner(managed); history.setAction(LedgerEntryChangeAction.UPDATE);
            history.setCreatedAt(LocalDateTime.now()); history.setEntryCount(1); history.setSummary("변경");
            history.setBeforeSnapshotJson("[]"); history.setAfterSnapshotJson("[]"); ledgerHistories.saveAndFlush(history);
            return new long[]{entry.getId(), history.getId()};
        });
    }
    private long[] recurring(AppUser owner) {
        return new TransactionTemplate(transactions).execute(status -> {
            AppUser managed = users.findById(owner.getId()).orElseThrow();
            var category = new CategoryGroup(); category.setOwner(managed); category.setName("정기 지출");
            category.setEntryType(EntryType.EXPENSE); ledgerCategories.saveAndFlush(category);
            var rule = new RecurringLedgerRule(); rule.setOwner(managed); rule.setCategoryGroup(category);
            rule.setTitle("정기 결제"); rule.setAmount(BigDecimal.valueOf(1000)); rule.setEntryType(EntryType.EXPENSE);
            rule.setDayOfMonth(19); rule.setMonthInterval(1); rule.setStartDate(LocalDate.of(2034, 6, 19));
            rule.setMode(RecurringLedgerMode.CONFIRM); rules.saveAndFlush(rule);
            var occurrence = new RecurringLedgerOccurrence(); occurrence.setRule(rule);
            occurrence.setScheduledDate(LocalDate.of(2034, 7, 19)); occurrence.setMode(RecurringLedgerMode.CONFIRM);
            occurrence.setStatus(RecurringLedgerOccurrenceStatus.PENDING); occurrences.saveAndFlush(occurrence);
            return new long[]{rule.getId(), occurrence.getId()};
        });
    }
    private FamilyCategory family(AppUser owner, AppUser member) {
        FamilyCategory value = new FamilyCategory(); value.setOwner(owner); value.setName("가족 앨범");
        familyCategories.saveAndFlush(value);
        FamilyCategoryMember link = new FamilyCategoryMember(); link.setCategory(value); link.setUser(member);
        familyMembers.saveAndFlush(link);
        return value;
    }
    private FamilyMediaAsset familyPhoto(FamilyCategory category, AppUser owner) {
        FamilyMediaAsset value = new FamilyMediaAsset(); value.setCategory(category); value.setOwner(owner);
        value.setOriginalFileName("photo.jpg"); value.setStoredFileName(UUID.randomUUID() + "-photo.jpg");
        value.setStoragePath("family-media/" + owner.getId() + "/" + category.getId() + "/" + value.getStoredFileName());
        value.setContentType("image/jpeg"); value.setFileSize(1L); value.setShared(true);
        return familyMedia.saveAndFlush(value);
    }
    private TravelShareGroup group(AppUser owner, AppUser... members) {
        TravelShareGroup value = new TravelShareGroup(); value.setOwner(owner); value.setName("공유 그룹");
        value.setCreatedAt(LocalDateTime.now()); value.setUpdatedAt(LocalDateTime.now()); groups.saveAndFlush(value);
        for (AppUser member : members) {
            TravelShareGroupMember link = new TravelShareGroupMember(); link.setGroup(value); link.setMember(member);
            link.setCreatedAt(LocalDateTime.now()); groupMembers.saveAndFlush(link);
        }
        return value;
    }
    private RecordShare share(TravelShareGroup group, AppUser sender, AppUser recipient, long sourceId) {
        RecordShare value = new RecordShare(); value.setGroupId(group.getId()); value.setGroupName(group.getName());
        value.setKind(RecordShareKind.LEDGER); value.setSourceId(sourceId); value.setSender(sender); value.setRecipient(recipient);
        value.setStatus(RecordShareStatus.PENDING); value.setTitle("공유 거래"); value.setCreatedAt(LocalDateTime.now());
        return shares.saveAndFlush(value);
    }
    private SocialAccount kakao(AppUser owner, String subject) {
        SocialAccount value = new SocialAccount(); value.setUser(owner); value.setProvider(SocialLoginProvider.KAKAO);
        value.setProviderUserId(subject); value.setProviderAppId(1234L); return social.saveAndFlush(value);
    }
    private EmailVerificationToken signupToken(AppUser owner) throws Exception {
        EmailVerificationToken value = new EmailVerificationToken(); value.setUser(owner); value.setTokenHash(hash());
        value.setCreatedAt(LocalDateTime.now()); value.setExpiresAt(LocalDateTime.now().plusHours(1)); return signupTokens.saveAndFlush(value);
    }
    private AccountEmailVerification profileToken(AppUser owner) throws Exception {
        AccountEmailVerification value = new AccountEmailVerification(); value.setUser(owner); value.setTargetEmail(unique() + "@example.test");
        value.setTokenHash(hash()); value.setCreatedAt(LocalDateTime.now()); value.setExpiresAt(LocalDateTime.now().plusHours(1));
        return profileTokens.saveAndFlush(value);
    }
    private String hash() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(unique().getBytes(StandardCharsets.UTF_8)));
    }
    private String inviteHash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.trim().getBytes(StandardCharsets.UTF_8)));
    }
}
