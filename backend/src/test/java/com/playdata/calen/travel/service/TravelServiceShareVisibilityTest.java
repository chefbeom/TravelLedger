package com.playdata.calen.travel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.service.LedgerTravelBridgeService;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelPlanShare;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.dto.TravelSharedExhibitPageResponse;
import com.playdata.calen.travel.repository.TravelBudgetItemRepository;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import com.playdata.calen.travel.repository.TravelPhotoClusterMemberRepository;
import com.playdata.calen.travel.repository.TravelPhotoClusterRepository;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import com.playdata.calen.travel.repository.TravelPlanShareRepository;
import com.playdata.calen.travel.repository.TravelRouteSegmentRepository;
import com.playdata.calen.travel.repository.TravelShareGroupMemberRepository;
import com.playdata.calen.travel.repository.TravelShareGroupRepository;
import java.time.LocalDate;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TravelServiceShareVisibilityTest {

    private AppUserService appUserService;
    private TravelPlanRepository travelPlanRepository;
    private TravelBudgetItemRepository travelBudgetItemRepository;
    private TravelPlanShareRepository travelPlanShareRepository;
    private TravelService service;

    @BeforeEach
    void setUp() {
        appUserService = mock(AppUserService.class);
        travelPlanRepository = mock(TravelPlanRepository.class);
        travelBudgetItemRepository = mock(TravelBudgetItemRepository.class);
        TravelExpenseRecordRepository travelExpenseRecordRepository = mock(TravelExpenseRecordRepository.class);
        TravelMediaAssetRepository travelMediaAssetRepository = mock(TravelMediaAssetRepository.class);
        TravelPhotoClusterRepository travelPhotoClusterRepository = mock(TravelPhotoClusterRepository.class);
        TravelPhotoClusterMemberRepository travelPhotoClusterMemberRepository = mock(TravelPhotoClusterMemberRepository.class);
        TravelRouteSegmentRepository travelRouteSegmentRepository = mock(TravelRouteSegmentRepository.class);
        travelPlanShareRepository = mock(TravelPlanShareRepository.class);
        TravelShareGroupRepository travelShareGroupRepository = mock(TravelShareGroupRepository.class);
        TravelShareGroupMemberRepository travelShareGroupMemberRepository = mock(TravelShareGroupMemberRepository.class);
        ExchangeRateService exchangeRateService = mock(ExchangeRateService.class);
        TravelMediaStorageService travelMediaStorageService = mock(TravelMediaStorageService.class);
        TravelDriveLinkService travelDriveLinkService = mock(TravelDriveLinkService.class);
        TravelPhotoGpsMetadataService travelPhotoGpsMetadataService = mock(TravelPhotoGpsMetadataService.class);
        TravelPhotoClusterService travelPhotoClusterService = mock(TravelPhotoClusterService.class);
        TravelMyMapPhotoClusterSnapshotService travelMyMapPhotoClusterSnapshotService = mock(TravelMyMapPhotoClusterSnapshotService.class);
        TravelPublicMediaTokenService travelPublicMediaTokenService = mock(TravelPublicMediaTokenService.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        LedgerTravelBridgeService ledgerTravelBridgeService = mock(LedgerTravelBridgeService.class);
        DataSource dataSource = mock(DataSource.class);

        service = new TravelService(
                appUserService,
                travelPlanRepository,
                travelBudgetItemRepository,
                travelExpenseRecordRepository,
                travelMediaAssetRepository,
                travelPhotoClusterRepository,
                travelPhotoClusterMemberRepository,
                travelRouteSegmentRepository,
                travelPlanShareRepository,
                travelShareGroupRepository,
                travelShareGroupMemberRepository,
                exchangeRateService,
                travelMediaStorageService,
                travelDriveLinkService,
                travelPhotoGpsMetadataService,
                travelPhotoClusterService,
                travelMyMapPhotoClusterSnapshotService,
                travelPublicMediaTokenService,
                redisCacheService,
                ledgerTravelBridgeService,
                new ObjectMapper(),
                dataSource
        );
    }

    @Test
    void rejectsPublicSharingForIncompletePlan() {
        TravelPlan plan = plan(10L, 7L, TravelPlanStatus.PLANNED);
        when(travelPlanRepository.findByIdAndOwnerId(10L, 7L)).thenReturn(Optional.of(plan));

        assertThrows(BadRequestException.class, () -> service.updatePlanPublicShare(7L, 10L, true));
        assertFalse(Boolean.TRUE.equals(plan.getPublicShared()));
        assertNull(plan.getPublicSharedAt());
    }

    @Test
    void allowsPublicShareDisableForIncompletePlan() {
        TravelPlan plan = plan(10L, 7L, TravelPlanStatus.PLANNED);
        plan.setPublicShared(true);
        when(travelPlanRepository.findByIdAndOwnerId(10L, 7L)).thenReturn(Optional.of(plan));

        service.updatePlanPublicShare(7L, 10L, false);

        assertFalse(Boolean.TRUE.equals(plan.getPublicShared()));
        assertNull(plan.getPublicSharedAt());
    }

    @Test
    void sharedExhibitListOnlyUsesCompletedPlans() {
        when(appUserService.getRequiredUser(7L)).thenReturn(user(7L));
        when(travelPlanShareRepository.findAllByRecipientIdAndPlan_StatusOrderByCreatedAtDescIdDesc(
                7L,
                TravelPlanStatus.COMPLETED,
                PageRequest.of(0, 5)
        )).thenReturn(new PageImpl<>(java.util.List.of()));

        TravelSharedExhibitPageResponse response = service.getSharedExhibits(7L, 0, 5);

        assertTrue(response.items().isEmpty());
        verify(travelPlanShareRepository).findAllByRecipientIdAndPlan_StatusOrderByCreatedAtDescIdDesc(
                7L,
                TravelPlanStatus.COMPLETED,
                PageRequest.of(0, 5)
        );
        verify(travelPlanShareRepository, never()).findAllByRecipientIdOrderByCreatedAtDescIdDesc(
                7L,
                PageRequest.of(0, 5)
        );
    }

    @Test
    void rejectsSharedExhibitDetailWhenPlanIsNoLongerCompleted() {
        TravelPlanShare share = new TravelPlanShare();
        share.setId(100L);
        share.setPlan(plan(10L, 3L, TravelPlanStatus.PLANNED));
        share.setSharedBy(user(3L));
        share.setRecipient(user(7L));
        when(appUserService.getRequiredUser(7L)).thenReturn(user(7L));
        when(travelPlanShareRepository.findByIdAndRecipientId(100L, 7L)).thenReturn(Optional.of(share));

        assertThrows(NotFoundException.class, () -> service.getSharedExhibit(7L, 100L));
        verify(travelBudgetItemRepository, never()).findAllByPlanIdAndPlanOwnerIdOrderByDisplayOrderAscIdAsc(anyLong(), anyLong());
    }

    private TravelPlan plan(Long id, Long ownerId, TravelPlanStatus status) {
        TravelPlan plan = new TravelPlan();
        plan.setId(id);
        plan.setOwner(user(ownerId));
        plan.setName("Trip");
        plan.setDestination("Seoul");
        plan.setStartDate(LocalDate.of(2026, 4, 1));
        plan.setEndDate(LocalDate.of(2026, 4, 2));
        plan.setStatus(status);
        plan.setPublicShared(false);
        return plan;
    }

    private AppUser user(Long id) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setLoginId("user" + id);
        user.setDisplayName("User " + id);
        user.setPasswordHash("hash");
        return user;
    }
}
