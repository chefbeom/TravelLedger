package com.playdata.calen.travel.service;

import com.playdata.calen.travel.domain.policy.TravelPhotoClusterPolicy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.ledger.application.port.in.ReflectTravelLedgerUseCase;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.TravelMediaType;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.dto.TravelPlanRequest;
import com.playdata.calen.travel.dto.TravelPlanSummaryResponse;
import com.playdata.calen.travel.repository.TravelBudgetItemRepository;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import com.playdata.calen.travel.repository.TravelMapShareLinkRepository;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import com.playdata.calen.travel.repository.TravelPlanShareRepository;
import com.playdata.calen.travel.repository.TravelRouteSegmentRepository;
import com.playdata.calen.travel.repository.TravelShareGroupMemberRepository;
import com.playdata.calen.travel.repository.TravelShareGroupRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class TravelServiceCacheTest {

    @Test
    void returnsCachedPlansWithoutQueryingRepositories() {
        var users = mock(AppUserService.class);
        var plans = mock(TravelPlanRepository.class);
        var redis = mock(RedisCacheService.class);
        var summaries = new com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache(redis);
        var payload = mock(com.playdata.calen.travel.application.port.out.TravelRoutePayloadPort.class);
        var reads = new com.playdata.calen.travel.application.service.ReadOwnedTravelPlansService(
                new com.playdata.calen.travel.adapter.out.persistence.JpaOwnedTravelPlansAdapter(users, plans,
                        mock(TravelBudgetItemRepository.class), mock(TravelExpenseRecordRepository.class),
                        mock(TravelMediaAssetRepository.class), mock(TravelRouteSegmentRepository.class), payload),
                new com.playdata.calen.travel.adapter.out.cache.OwnedTravelPlansCache(redis, summaries, 0), payload);
        var service = mock(TravelService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "planReads", reads);
        var cached = List.of(new TravelPlanSummaryResponse(10L, "Trip", "Seoul", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 3),
                "KRW", 1, "PLANNED", "#3182F6", null, null, null, 0, 0, 0, 0, 0, null, null, null));
        when(redis.get(eq("travel:plans:7"), org.mockito.ArgumentMatchers.<TypeReference<List<TravelPlanSummaryResponse>>>any())).thenReturn(cached);
        assertEquals(cached, service.getPlans(7L));
        verify(users).getRequiredUser(7L);
        verify(plans, never()).findAllByOwnerIdOrderByStartDateDescIdDesc(7L);
    }

    @Test
    void planCreationDelegatesToTheMigratedUseCase() {
        TravelService service = mock(TravelService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        var writes = mock(com.playdata.calen.travel.application.port.in.WriteTravelPlanUseCase.class);
        ReflectionTestUtils.setField(service, "planWrites", writes);
        var request = new TravelPlanRequest("April Trip", "Tokyo", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 3),
                "KRW", 2, "PLANNED", "#3182F6", "memo");
        var draft = com.playdata.calen.travel.adapter.support.TravelPlanMapper.draft(request);
        var result = new com.playdata.calen.travel.domain.model.TravelPlanData.Summary(99L,
                com.playdata.calen.travel.domain.policy.TravelPlanPolicy.prepare(draft),
                com.playdata.calen.travel.domain.model.TravelPlanData.Metrics.empty());
        when(writes.create(7L, draft)).thenReturn(result);
        assertEquals(99L, service.createPlan(7L, request).id());
        verify(writes).create(7L, draft);
    }

    @Test
    void cachesOwnedMediaDownloadMetadataForRepeatedThumbnailRequests() {
        AppUserService appUserService = mock(AppUserService.class);
        TravelPlanRepository travelPlanRepository = mock(TravelPlanRepository.class);
        TravelBudgetItemRepository travelBudgetItemRepository = mock(TravelBudgetItemRepository.class);
        TravelExpenseRecordRepository travelExpenseRecordRepository = mock(TravelExpenseRecordRepository.class);
        TravelMediaAssetRepository travelMediaAssetRepository = mock(TravelMediaAssetRepository.class);
        TravelMapShareLinkRepository travelMapShareLinkRepository = mock(TravelMapShareLinkRepository.class);
        TravelRouteSegmentRepository travelRouteSegmentRepository = mock(TravelRouteSegmentRepository.class);
        TravelPlanShareRepository travelPlanShareRepository = mock(TravelPlanShareRepository.class);
        TravelShareGroupRepository travelShareGroupRepository = mock(TravelShareGroupRepository.class);
        TravelShareGroupMemberRepository travelShareGroupMemberRepository = mock(TravelShareGroupMemberRepository.class);
        ExchangeRateService exchangeRateService = mock(ExchangeRateService.class);
        TravelMediaStorageService travelMediaStorageService = mock(TravelMediaStorageService.class);
        TravelDriveLinkService travelDriveLinkService = mock(TravelDriveLinkService.class);
        TravelPhotoGpsMetadataService travelPhotoGpsMetadataService = mock(TravelPhotoGpsMetadataService.class);
        TravelPhotoClusterPolicy travelPhotoClusterPolicy = mock(TravelPhotoClusterPolicy.class);
        TravelMyMapPhotoClusterSnapshotService travelMyMapPhotoClusterSnapshotService = mock(TravelMyMapPhotoClusterSnapshotService.class);
        TravelPublicMediaTokenService travelPublicMediaTokenService = mock(TravelPublicMediaTokenService.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        ReflectTravelLedgerUseCase ledgerTravelBridgeService = mock(ReflectTravelLedgerUseCase.class);
        DataSource dataSource = mock(DataSource.class);

        TravelService service = new TravelService(
                mock(com.playdata.calen.travel.application.port.in.ReadTravelReferenceUseCase.class),
                ledgerTravelBridgeService,
                mock(com.playdata.calen.travel.application.port.in.WriteTravelBudgetUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.WriteTravelPlanUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.WriteTravelRecordUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ManageTravelPhotoClustersUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.WriteTravelRouteUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ManageTravelRouteFilesUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ManageOwnedTravelMediaUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ManageTravelSharingUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ReadTravelExhibitsUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ReadOwnedTravelMapUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.TravelMapLinksUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ReadTravelAtlasUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ReadOwnedTravelPlansUseCase.class)
        );
        var ownedStore=new com.playdata.calen.travel.adapter.out.persistence.JpaTravelMediaAdapter(appUserService,
                mock(com.playdata.calen.account.repository.AppUserRepository.class),travelExpenseRecordRepository,travelMediaAssetRepository,travelDriveLinkService);
        ReflectionTestUtils.setField(service,"ownedMedia",new com.playdata.calen.travel.application.service.ManageOwnedTravelMediaService(
                ownedStore,new com.playdata.calen.travel.adapter.out.storage.TravelMediaStorageAdapter(travelMediaStorageService),
                new com.playdata.calen.travel.adapter.out.cache.OwnedTravelMediaCache(300L),
                new com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache(redisCacheService),
                mock(com.playdata.calen.travel.application.port.out.TravelClusterRefreshPort.class)));

        TravelMediaAsset mediaAsset = mock(TravelMediaAsset.class);
        when(mediaAsset.getStoragePath()).thenReturn("travel-media/7/photo.jpg");
        when(mediaAsset.getContentType()).thenReturn("image/jpeg");
        when(mediaAsset.getOriginalFileName()).thenReturn("photo.jpg");
        when(travelMediaAssetRepository.findByIdAndPlanOwnerId(9L, 7L)).thenReturn(Optional.of(mediaAsset));

        TravelService.MediaDownload firstDownload = service.getMediaDownload(7L, 9L);
        TravelService.MediaDownload secondDownload = service.getMediaDownload(7L, 9L);

        assertEquals(firstDownload, secondDownload);
        verify(travelMediaAssetRepository, times(1)).findByIdAndPlanOwnerId(9L, 7L);
    }

    @Test
    void evictsOwnedMediaDownloadCacheWhenMediaIsDeleted() {
        AppUserService appUserService = mock(AppUserService.class);
        TravelPlanRepository travelPlanRepository = mock(TravelPlanRepository.class);
        TravelBudgetItemRepository travelBudgetItemRepository = mock(TravelBudgetItemRepository.class);
        TravelExpenseRecordRepository travelExpenseRecordRepository = mock(TravelExpenseRecordRepository.class);
        TravelMediaAssetRepository travelMediaAssetRepository = mock(TravelMediaAssetRepository.class);
        TravelMapShareLinkRepository travelMapShareLinkRepository = mock(TravelMapShareLinkRepository.class);
        TravelRouteSegmentRepository travelRouteSegmentRepository = mock(TravelRouteSegmentRepository.class);
        TravelPlanShareRepository travelPlanShareRepository = mock(TravelPlanShareRepository.class);
        TravelShareGroupRepository travelShareGroupRepository = mock(TravelShareGroupRepository.class);
        TravelShareGroupMemberRepository travelShareGroupMemberRepository = mock(TravelShareGroupMemberRepository.class);
        ExchangeRateService exchangeRateService = mock(ExchangeRateService.class);
        TravelMediaStorageService travelMediaStorageService = mock(TravelMediaStorageService.class);
        TravelDriveLinkService travelDriveLinkService = mock(TravelDriveLinkService.class);
        TravelPhotoGpsMetadataService travelPhotoGpsMetadataService = mock(TravelPhotoGpsMetadataService.class);
        TravelPhotoClusterPolicy travelPhotoClusterPolicy = mock(TravelPhotoClusterPolicy.class);
        TravelMyMapPhotoClusterSnapshotService travelMyMapPhotoClusterSnapshotService = mock(TravelMyMapPhotoClusterSnapshotService.class);
        TravelPublicMediaTokenService travelPublicMediaTokenService = mock(TravelPublicMediaTokenService.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        ReflectTravelLedgerUseCase ledgerTravelBridgeService = mock(ReflectTravelLedgerUseCase.class);
        DataSource dataSource = mock(DataSource.class);

        TravelService service = new TravelService(
                mock(com.playdata.calen.travel.application.port.in.ReadTravelReferenceUseCase.class),
                ledgerTravelBridgeService,
                mock(com.playdata.calen.travel.application.port.in.WriteTravelBudgetUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.WriteTravelPlanUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.WriteTravelRecordUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ManageTravelPhotoClustersUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.WriteTravelRouteUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ManageTravelRouteFilesUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ManageOwnedTravelMediaUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ManageTravelSharingUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ReadTravelExhibitsUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ReadOwnedTravelMapUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.TravelMapLinksUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ReadTravelAtlasUseCase.class),
                mock(com.playdata.calen.travel.application.port.in.ReadOwnedTravelPlansUseCase.class)
        );
        var ownedStore=new com.playdata.calen.travel.adapter.out.persistence.JpaTravelMediaAdapter(appUserService,
                mock(com.playdata.calen.account.repository.AppUserRepository.class),travelExpenseRecordRepository,travelMediaAssetRepository,travelDriveLinkService);
        ReflectionTestUtils.setField(service,"ownedMedia",new com.playdata.calen.travel.application.service.ManageOwnedTravelMediaService(
                ownedStore,new com.playdata.calen.travel.adapter.out.storage.TravelMediaStorageAdapter(travelMediaStorageService),
                new com.playdata.calen.travel.adapter.out.cache.OwnedTravelMediaCache(300L),
                new com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache(redisCacheService),
                mock(com.playdata.calen.travel.application.port.out.TravelClusterRefreshPort.class)));

        TravelMediaAsset mediaAsset = mock(TravelMediaAsset.class);
        when(mediaAsset.getStoragePath()).thenReturn("travel-media/7/receipt.jpg");
        when(mediaAsset.getContentType()).thenReturn("image/jpeg");
        when(mediaAsset.getOriginalFileName()).thenReturn("receipt.jpg");
        when(mediaAsset.getMediaType()).thenReturn(TravelMediaType.RECEIPT);
        when(travelMediaAssetRepository.getReferenceById(9L)).thenReturn(mediaAsset);
        when(travelMediaAssetRepository.findByIdAndPlanOwnerId(9L, 7L)).thenReturn(Optional.of(mediaAsset));

        service.getMediaDownload(7L, 9L);
        service.deleteMedia(7L, 9L);
        service.getMediaDownload(7L, 9L);

        verify(travelMediaAssetRepository, times(3)).findByIdAndPlanOwnerId(9L, 7L);
    }
}
