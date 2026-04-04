package com.playdata.calen.travel.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.dto.TravelPlanRequest;
import com.playdata.calen.travel.dto.TravelPlanSummaryResponse;
import com.playdata.calen.travel.repository.TravelBudgetItemRepository;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import com.playdata.calen.travel.repository.TravelPlanShareRepository;
import com.playdata.calen.travel.repository.TravelRouteSegmentRepository;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TravelServiceCacheTest {

    @Test
    void returnsCachedPlansWithoutQueryingRepositories() {
        AppUserService appUserService = mock(AppUserService.class);
        TravelPlanRepository travelPlanRepository = mock(TravelPlanRepository.class);
        TravelBudgetItemRepository travelBudgetItemRepository = mock(TravelBudgetItemRepository.class);
        TravelExpenseRecordRepository travelExpenseRecordRepository = mock(TravelExpenseRecordRepository.class);
        TravelMediaAssetRepository travelMediaAssetRepository = mock(TravelMediaAssetRepository.class);
        TravelRouteSegmentRepository travelRouteSegmentRepository = mock(TravelRouteSegmentRepository.class);
        TravelPlanShareRepository travelPlanShareRepository = mock(TravelPlanShareRepository.class);
        ExchangeRateService exchangeRateService = mock(ExchangeRateService.class);
        TravelMediaStorageService travelMediaStorageService = mock(TravelMediaStorageService.class);
        TravelPhotoGpsMetadataService travelPhotoGpsMetadataService = mock(TravelPhotoGpsMetadataService.class);
        TravelPhotoClusterService travelPhotoClusterService = mock(TravelPhotoClusterService.class);
        TravelPublicMediaTokenService travelPublicMediaTokenService = mock(TravelPublicMediaTokenService.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        DataSource dataSource = mock(DataSource.class);

        TravelService service = new TravelService(
                appUserService,
                travelPlanRepository,
                travelBudgetItemRepository,
                travelExpenseRecordRepository,
                travelMediaAssetRepository,
                travelRouteSegmentRepository,
                travelPlanShareRepository,
                exchangeRateService,
                travelMediaStorageService,
                travelPhotoGpsMetadataService,
                travelPhotoClusterService,
                travelPublicMediaTokenService,
                redisCacheService,
                new ObjectMapper(),
                dataSource
        );

        when(appUserService.getRequiredUser(7L)).thenReturn(new AppUser());
        List<TravelPlanSummaryResponse> cachedResponse = List.of(new TravelPlanSummaryResponse(
                10L,
                "Trip",
                "Seoul",
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 3),
                "KRW",
                1,
                "PLANNED",
                "#3182F6",
                null,
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                null,
                null,
                null
        ));

        when(redisCacheService.get(eq("travel:plans:7"), org.mockito.ArgumentMatchers.<TypeReference<List<TravelPlanSummaryResponse>>>any()))
                .thenReturn(cachedResponse);

        List<TravelPlanSummaryResponse> result = service.getPlans(7L);

        assertEquals(cachedResponse, result);
        verify(travelPlanRepository, never()).findAllByOwnerIdOrderByStartDateDescIdDesc(7L);
    }

    @Test
    void invalidatesUserTravelSummaryCachesAfterCreatingPlan() {
        AppUserService appUserService = mock(AppUserService.class);
        TravelPlanRepository travelPlanRepository = mock(TravelPlanRepository.class);
        TravelBudgetItemRepository travelBudgetItemRepository = mock(TravelBudgetItemRepository.class);
        TravelExpenseRecordRepository travelExpenseRecordRepository = mock(TravelExpenseRecordRepository.class);
        TravelMediaAssetRepository travelMediaAssetRepository = mock(TravelMediaAssetRepository.class);
        TravelRouteSegmentRepository travelRouteSegmentRepository = mock(TravelRouteSegmentRepository.class);
        TravelPlanShareRepository travelPlanShareRepository = mock(TravelPlanShareRepository.class);
        ExchangeRateService exchangeRateService = mock(ExchangeRateService.class);
        TravelMediaStorageService travelMediaStorageService = mock(TravelMediaStorageService.class);
        TravelPhotoGpsMetadataService travelPhotoGpsMetadataService = mock(TravelPhotoGpsMetadataService.class);
        TravelPhotoClusterService travelPhotoClusterService = mock(TravelPhotoClusterService.class);
        TravelPublicMediaTokenService travelPublicMediaTokenService = mock(TravelPublicMediaTokenService.class);
        RedisCacheService redisCacheService = mock(RedisCacheService.class);
        DataSource dataSource = mock(DataSource.class);

        TravelService service = new TravelService(
                appUserService,
                travelPlanRepository,
                travelBudgetItemRepository,
                travelExpenseRecordRepository,
                travelMediaAssetRepository,
                travelRouteSegmentRepository,
                travelPlanShareRepository,
                exchangeRateService,
                travelMediaStorageService,
                travelPhotoGpsMetadataService,
                travelPhotoClusterService,
                travelPublicMediaTokenService,
                redisCacheService,
                new ObjectMapper(),
                dataSource
        );

        AppUser owner = new AppUser();
        owner.setId(7L);
        when(appUserService.getRequiredUser(7L)).thenReturn(owner);
        when(travelPlanRepository.save(any(TravelPlan.class))).thenAnswer(invocation -> {
            TravelPlan plan = invocation.getArgument(0);
            plan.setId(99L);
            return plan;
        });

        TravelPlanRequest request = new TravelPlanRequest(
                "April Trip",
                "Tokyo",
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 3),
                "KRW",
                2,
                "PLANNED",
                "#3182F6",
                "memo"
        );

        service.createPlan(7L, request);

        verify(redisCacheService).delete(
                "travel:plans:7",
                "travel:portfolio:7",
                "travel:mymap:overview:7"
        );
    }
}
