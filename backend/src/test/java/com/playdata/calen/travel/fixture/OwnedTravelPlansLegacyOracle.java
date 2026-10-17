package com.playdata.calen.travel.fixture;

import com.playdata.calen.ledger.adapter.support.LedgerTravelMapper;
import com.playdata.calen.travel.adapter.support.TravelLedgerReflectionMapper;
import com.playdata.calen.travel.adapter.support.TravelAtlasMapper;
import com.playdata.calen.travel.adapter.support.TravelMapLinkMapper;
import com.playdata.calen.travel.adapter.support.TravelRouteMapper;
import com.playdata.calen.travel.adapter.support.TravelRouteUploadMapper;
import com.playdata.calen.travel.adapter.support.TravelMediaUploadMapper;
import com.playdata.calen.travel.adapter.support.TravelSharingMapper;
import com.playdata.calen.travel.adapter.support.TravelExhibitMapper;
import com.playdata.calen.travel.adapter.support.TravelOwnedMapMapper;
import com.playdata.calen.travel.application.port.in.ReadOwnedTravelMapUseCase;
import com.playdata.calen.travel.adapter.support.TravelPlanDetailMapper;
import com.playdata.calen.travel.application.port.in.ManageOwnedTravelMediaUseCase;
import com.playdata.calen.travel.application.port.in.ManageTravelSharingUseCase;
import com.playdata.calen.travel.application.port.in.ReadTravelExhibitsUseCase;
import com.playdata.calen.travel.application.port.in.ManageTravelRouteFilesUseCase;
import com.playdata.calen.travel.application.port.in.WriteTravelRouteUseCase;
import com.playdata.calen.travel.application.port.out.TravelRoutePayloadPort;
import com.playdata.calen.travel.adapter.support.TravelClusterDetailMapper;
import com.playdata.calen.travel.application.port.in.ManageTravelPhotoClustersUseCase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.dto.LedgerEntryResponse;
import com.playdata.calen.travel.adapter.out.cache.TravelSummaryCache;
import com.playdata.calen.travel.adapter.support.TravelBudgetMapper;
import com.playdata.calen.travel.application.port.in.WriteTravelBudgetUseCase;
import com.playdata.calen.travel.domain.policy.TravelMoneyPolicy;
import com.playdata.calen.travel.domain.policy.TravelPlanPolicy;
import com.playdata.calen.travel.adapter.support.TravelPlanMapper;
import com.playdata.calen.travel.adapter.support.TravelRecordMapper;
import com.playdata.calen.travel.application.port.in.WriteTravelRecordUseCase;
import com.playdata.calen.travel.domain.policy.TravelRecordPolicy;
import com.playdata.calen.travel.domain.policy.TravelCoordinatePolicy;
import com.playdata.calen.travel.application.port.in.WriteTravelPlanUseCase;
import com.playdata.calen.travel.domain.TravelBudgetItem;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.domain.TravelRouteSegment;
import com.playdata.calen.travel.dto.TravelBudgetItemRequest;
import com.playdata.calen.travel.dto.TravelBudgetItemResponse;
import com.playdata.calen.travel.dto.TravelCategoryCatalogResponse;
import com.playdata.calen.travel.dto.TravelCommunityFeedPageResponse;
import com.playdata.calen.travel.dto.TravelExchangeRateResponse;
import com.playdata.calen.travel.dto.TravelExpenseRecordRequest;
import com.playdata.calen.travel.dto.TravelExpenseRecordResponse;
import com.playdata.calen.travel.dto.TravelMediaResponse;
import com.playdata.calen.travel.dto.TravelMediaUploadCompleteRequest;
import com.playdata.calen.travel.dto.TravelMediaUploadPrepareRequest;
import com.playdata.calen.travel.dto.TravelMediaUploadPrepareResponse;
import com.playdata.calen.travel.dto.TravelMyMapMarkerDetailBundleResponse;
import com.playdata.calen.travel.dto.TravelMyMapOverviewResponse;
import com.playdata.calen.travel.dto.TravelMyMapPhotoClusterPageResponse;
import com.playdata.calen.travel.dto.TravelMemoryRecordRequest;
import com.playdata.calen.travel.dto.TravelMemoryRecordResponse;
import com.playdata.calen.travel.dto.TravelMapShareLinkRequest;
import com.playdata.calen.travel.dto.TravelMapShareLinkResponse;
import com.playdata.calen.travel.dto.TravelLoginMapPreviewResponse;
import com.playdata.calen.travel.dto.TravelPhotoFrameMediaResponse;
import com.playdata.calen.travel.dto.TravelPlanDetailResponse;
import com.playdata.calen.travel.dto.TravelPlanPublicShareResponse;
import com.playdata.calen.travel.dto.TravelPlanRequest;
import com.playdata.calen.travel.dto.TravelPlanShareResponse;
import com.playdata.calen.travel.dto.TravelPlanSummaryResponse;
import com.playdata.calen.travel.dto.TravelPortfolioResponse;
import com.playdata.calen.travel.dto.TravelPublicMapShareResponse;
import com.playdata.calen.travel.dto.TravelPublicTripsOverviewResponse;
import com.playdata.calen.travel.dto.TravelRouteGpxUploadAbortRequest;
import com.playdata.calen.travel.dto.TravelRouteGpxUploadCompleteRequest;
import com.playdata.calen.travel.dto.TravelRouteGpxUploadPrepareRequest;
import com.playdata.calen.travel.dto.TravelRouteGpxUploadPrepareResponse;
import com.playdata.calen.travel.dto.TravelRoutePointResponse;
import com.playdata.calen.travel.dto.TravelRouteSegmentRequest;
import com.playdata.calen.travel.dto.TravelRouteSegmentResponse;
import com.playdata.calen.travel.dto.TravelShareGroupRequest;
import com.playdata.calen.travel.dto.TravelShareGroupResponse;
import com.playdata.calen.travel.dto.TravelShareRecipientResponse;
import com.playdata.calen.travel.dto.TravelSharedExhibitDetailResponse;
import com.playdata.calen.travel.dto.TravelSharedExhibitPageResponse;
import com.playdata.calen.travel.repository.TravelBudgetItemRepository;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import com.playdata.calen.travel.repository.TravelRouteSegmentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Frozen owned summary/detail/portfolio implementation before the 2026-10-17 extraction. */
@RequiredArgsConstructor
public class OwnedTravelPlansLegacyOracle {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal ZERO_DISTANCE = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
    private static final String KRW = "KRW";
    private static final TypeReference<List<TravelPlanSummaryResponse>> TRAVEL_PLAN_SUMMARIES_TYPE = new TypeReference<>() {
    };
    private static final List<String> DEFAULT_CURRENCIES = List.of("KRW", "USD", "JPY", "CNY", "EUR");
    private static final List<String> DEFAULT_BUDGET_CATEGORIES = List.of(
            "Flight",
            "Stay",
            "Transport",
            "Food",
            "Shopping",
            "Ticket",
            "Insurance",
            "Etc"
    );
    private static final List<String> DEFAULT_MEMORY_CATEGORIES = List.of(
            "Spot",
            "Cafe",
            "View",
            "Meal",
            "Hotel",
            "Transit",
            "Shopping"
    );
    private static final Comparator<TravelBudgetItem> BUDGET_ITEM_ORDER = Comparator
            .comparingInt((TravelBudgetItem item) -> item.getDisplayOrder() != null ? item.getDisplayOrder() : 0)
            .thenComparing(TravelBudgetItem::getId);
    private static final Comparator<TravelExpenseRecord> RECORD_ORDER = Comparator
            .comparing(TravelExpenseRecord::getExpenseDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(TravelExpenseRecord::getExpenseTime, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(TravelExpenseRecord::getId, Comparator.reverseOrder());
    private static final Comparator<TravelMediaAsset> MEDIA_ORDER = Comparator
            .comparing(TravelMediaAsset::getUploadedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(TravelMediaAsset::getId, Comparator.reverseOrder());
    private static final Comparator<TravelRouteSegment> ROUTE_ORDER = Comparator
            .comparing(TravelRouteSegment::getRouteDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(TravelRouteSegment::getId, Comparator.reverseOrder());

    private final AppUserService appUserService;
    private final TravelPlanRepository travelPlanRepository;
    private final TravelBudgetItemRepository travelBudgetItemRepository;
    private final TravelExpenseRecordRepository travelExpenseRecordRepository;
    private final TravelMediaAssetRepository travelMediaAssetRepository;
    private final TravelRouteSegmentRepository travelRouteSegmentRepository;
    private final RedisCacheService redisCacheService;
    private final TravelSummaryCache summaryCache;
    private final TravelRoutePayloadPort routePayload;
    private org.springframework.transaction.PlatformTransactionManager summaryTransactionManager;
    private long travelSummaryCacheTtlSeconds;

    public List<TravelPlanSummaryResponse> getPlans(Long userId) {
        return summaryCache.execute(buildPlansCacheKey(userId), () -> inSummaryTransaction(true, () -> loadPlans(userId)));
    }

    private List<TravelPlanSummaryResponse> loadPlans(Long userId) {
        appUserService.getRequiredUser(userId);

        List<TravelPlanSummaryResponse> cachedPlans = redisCacheService.get(buildPlansCacheKey(userId), TRAVEL_PLAN_SUMMARIES_TYPE);
        if (cachedPlans != null) {
            return cachedPlans;
        }

        List<TravelPlan> plans = travelPlanRepository.findAllByOwnerIdOrderByStartDateDescIdDesc(userId);
        Map<Long, TravelBudgetItemRepository.PlanBudgetAggregate> budgetByPlan = travelBudgetItemRepository
                .summarizeByPlanOwnerId(userId).stream()
                .collect(Collectors.toMap(TravelBudgetItemRepository.PlanBudgetAggregate::getPlanId, Function.identity()));
        Map<Long, TravelExpenseRecordRepository.PlanRecordAggregate> recordsByPlan = travelExpenseRecordRepository
                .summarizeByPlanOwnerId(userId, TravelRecordType.LEDGER, TravelRecordType.MEMORY).stream()
                .collect(Collectors.toMap(TravelExpenseRecordRepository.PlanRecordAggregate::getPlanId, Function.identity()));
        Map<Long, TravelMediaAssetRepository.PlanMediaAggregate> mediaByPlan = travelMediaAssetRepository
                .summarizeByPlanOwnerId(userId).stream()
                .collect(Collectors.toMap(TravelMediaAssetRepository.PlanMediaAggregate::getPlanId, Function.identity()));
        Map<Long, TravelRouteSegmentRepository.PlanRouteAggregate> routesByPlan = travelRouteSegmentRepository
                .summarizeByPlanOwnerId(userId).stream()
                .collect(Collectors.toMap(TravelRouteSegmentRepository.PlanRouteAggregate::getPlanId, Function.identity()));

        List<TravelPlanSummaryResponse> summaries = plans.stream()
                .map(plan -> toPlanSummary(
                        plan,
                        budgetByPlan.get(plan.getId()),
                        recordsByPlan.get(plan.getId()),
                        mediaByPlan.get(plan.getId()),
                        routesByPlan.get(plan.getId())
                ))
                .toList();
        cacheTravelSummary(buildPlansCacheKey(userId), summaries);
        return summaries;
    }

    public TravelPlanDetailResponse getPlan(Long userId, Long planId) {
        appUserService.getRequiredUser(userId);
        TravelPlan plan = getRequiredPlan(userId, planId);
        List<TravelBudgetItem> budgetItems = getPlanBudgetItems(userId, planId);
        List<TravelExpenseRecord> records = getPlanRecords(userId, planId);
        List<TravelExpenseRecord> memoryRecords = getPlanMemoryRecords(userId, planId);
        List<TravelMediaAsset> mediaItems = getPlanMedia(userId, planId);
        List<TravelRouteSegment> routeSegments = getPlanRoutes(userId, planId);
        return toPlanDetail(plan, budgetItems, records, memoryRecords, mediaItems, routeSegments);
    }

    public TravelPortfolioResponse getPortfolio(Long userId) {
        return summaryCache.execute(buildPortfolioCacheKey(userId), () -> inSummaryTransaction(true, () -> loadPortfolio(userId)));
    }

    private TravelPortfolioResponse loadPortfolio(Long userId) {
        appUserService.getRequiredUser(userId);

        TravelPortfolioResponse cachedPortfolio = redisCacheService.get(buildPortfolioCacheKey(userId), TravelPortfolioResponse.class);
        if (cachedPortfolio != null) {
            return cachedPortfolio;
        }

        List<TravelPlan> plans = travelPlanRepository.findAllByOwnerIdOrderByStartDateDescIdDesc(userId);
        List<TravelBudgetItem> budgetItems = travelBudgetItemRepository.findAllByPlanOwnerId(userId).stream()
                .sorted(BUDGET_ITEM_ORDER)
                .toList();
        List<TravelExpenseRecord> allRecords = travelExpenseRecordRepository.findAllByPlanOwnerId(userId).stream()
                .sorted(RECORD_ORDER)
                .toList();
        List<TravelExpenseRecord> records = allRecords.stream()
                .filter(this::isLedgerRecord)
                .toList();
        List<TravelExpenseRecord> memoryRecords = allRecords.stream()
                .filter(this::isMemoryRecord)
                .toList();
        List<TravelMediaAsset> mediaItems = travelMediaAssetRepository.findAllByPlanOwnerIdOrderByUploadedAtDescIdDesc(userId).stream()
                .sorted(MEDIA_ORDER)
                .toList();
        List<TravelRouteSegment> routeSegments = travelRouteSegmentRepository.findAllByPlanOwnerIdOrderByRouteDateDescIdDesc(userId).stream()
                .sorted(ROUTE_ORDER)
                .toList();

        Map<Long, List<TravelBudgetItem>> budgetItemsByPlan = budgetItems.stream()
                .collect(Collectors.groupingBy(item -> item.getPlan().getId()));
        Map<Long, List<TravelExpenseRecord>> recordsByPlan = records.stream()
                .collect(Collectors.groupingBy(item -> item.getPlan().getId()));
        Map<Long, List<TravelExpenseRecord>> memoryRecordsByPlan = memoryRecords.stream()
                .collect(Collectors.groupingBy(item -> item.getPlan().getId()));
        Map<Long, List<TravelMediaAsset>> mediaByPlan = mediaItems.stream()
                .collect(Collectors.groupingBy(item -> item.getPlan().getId()));
        Map<Long, List<TravelRouteSegment>> routesByPlan = routeSegments.stream()
                .collect(Collectors.groupingBy(item -> item.getPlan().getId()));

        List<TravelPlanSummaryResponse> planSummaries = plans.stream()
                .map(plan -> toPlanSummary(
                        plan,
                        budgetItemsByPlan.getOrDefault(plan.getId(), Collections.emptyList()),
                        recordsByPlan.getOrDefault(plan.getId(), Collections.emptyList()),
                        memoryRecordsByPlan.getOrDefault(plan.getId(), Collections.emptyList()),
                        mediaByPlan.getOrDefault(plan.getId(), Collections.emptyList()),
                        routesByPlan.getOrDefault(plan.getId(), Collections.emptyList())
                ))
                .toList();

        TravelPortfolioResponse response = new TravelPortfolioResponse(
                "ALL",
                "All trips",
                "All trips",
                KRW,
                1,
                sumAmountKrw(budgetItems, TravelBudgetItem::getAmountKrw),
                sumAmountKrw(records, TravelExpenseRecord::getAmountKrw),
                budgetItems.size(),
                records.size(),
                memoryRecords.size(),
                mediaItems.size(),
                routeSegments.size(),
                sumDistanceKm(routeSegments),
                sumInteger(routeSegments, TravelRouteSegment::getDurationMinutes),
                sumInteger(routeSegments, TravelRouteSegment::getStepCount),
                plans.size(),
                planSummaries,
                budgetItems.stream().map(this::toBudgetItemResponse).toList(),
                records.stream().map(this::toExpenseRecordResponse).toList(),
                memoryRecords.stream().map(this::toMemoryRecordResponse).toList(),
                mediaItems.stream().map(this::toMediaResponse).toList(),
                routeSegments.stream().map(this::toRouteSegmentResponse).toList()
        );
        cacheTravelSummary(buildPortfolioCacheKey(userId), response);
        return response;
    }

    private void cacheTravelSummary(String cacheKey, Object response) {
        Duration ttl = resolveTravelSummaryCacheTtl();
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        summaryCache.publishIfCurrent(cacheKey, response, ttl);
    }

    private <T> T inSummaryTransaction(boolean readOnly, java.util.function.Supplier<T> loader) {
        if (summaryTransactionManager == null) return loader.get();
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(summaryTransactionManager);
        template.setReadOnly(readOnly);
        return template.execute(status -> loader.get());
    }

    private Duration resolveTravelSummaryCacheTtl() {
        if (travelSummaryCacheTtlSeconds <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofSeconds(travelSummaryCacheTtlSeconds);
    }

    private String buildPlansCacheKey(Long userId) {
        return TravelSummaryCache.plansKey(userId);
    }

    private String buildPortfolioCacheKey(Long userId) {
        return TravelSummaryCache.portfolioKey(userId);
    }

    private TravelPlan getRequiredPlan(Long userId, Long planId) {
        return travelPlanRepository.findByIdAndOwnerId(planId, userId)
                .orElseThrow(() -> new NotFoundException("Travel plan not found."));
    }

    private List<TravelBudgetItem> getPlanBudgetItems(Long userId, Long planId) {
        return travelBudgetItemRepository.findAllByPlanIdAndPlanOwnerIdOrderByDisplayOrderAscIdAsc(planId, userId);
    }

    private List<TravelExpenseRecord> getPlanRecords(Long userId, Long planId) {
        return travelExpenseRecordRepository.findAllByPlanIdAndPlanOwnerIdOrderByExpenseDateDescIdDesc(planId, userId).stream()
                .filter(this::isLedgerRecord)
                .sorted(RECORD_ORDER)
                .toList();
    }

    private List<TravelExpenseRecord> getPlanMemoryRecords(Long userId, Long planId) {
        return travelExpenseRecordRepository.findAllByPlanIdAndPlanOwnerIdOrderByExpenseDateDescIdDesc(planId, userId).stream()
                .filter(this::isMemoryRecord)
                .sorted(RECORD_ORDER)
                .toList();
    }

    private List<TravelMediaAsset> getPlanMedia(Long userId, Long planId) {
        return travelMediaAssetRepository.findAllByPlanIdAndPlanOwnerIdOrderByUploadedAtDescIdDesc(planId, userId).stream()
                .sorted(MEDIA_ORDER)
                .toList();
    }

    private List<TravelRouteSegment> getPlanRoutes(Long userId, Long planId) {
        return travelRouteSegmentRepository.findAllByPlanIdAndPlanOwnerIdOrderByRouteDateDescIdDesc(planId, userId).stream()
                .sorted(ROUTE_ORDER)
                .toList();
    }

    private TravelPlanSummaryResponse toPlanSummary(
            TravelPlan plan,
            List<TravelBudgetItem> budgetItems,
            List<TravelExpenseRecord> records,
            List<TravelExpenseRecord> memoryRecords,
            List<TravelMediaAsset> mediaItems,
            List<TravelRouteSegment> routeSegments
    ) {
        return toPlanSummary(
                plan,
                sumAmountKrw(budgetItems, TravelBudgetItem::getAmountKrw),
                sumAmountKrw(records, TravelExpenseRecord::getAmountKrw),
                budgetItems.size(),
                records.size(),
                memoryRecords.size(),
                mediaItems.size(),
                routeSegments.size(),
                sumDistanceKm(routeSegments),
                sumInteger(routeSegments, TravelRouteSegment::getDurationMinutes),
                sumInteger(routeSegments, TravelRouteSegment::getStepCount)
        );
    }

    private TravelPlanSummaryResponse toPlanSummary(
            TravelPlan plan,
            TravelBudgetItemRepository.PlanBudgetAggregate budget,
            TravelExpenseRecordRepository.PlanRecordAggregate records,
            TravelMediaAssetRepository.PlanMediaAggregate media,
            TravelRouteSegmentRepository.PlanRouteAggregate routes
    ) {
        return toPlanSummary(
                plan,
                normalizeAmountKrw(budget != null ? budget.getTotalAmountKrw() : null),
                normalizeAmountKrw(records != null ? records.getActualTotalKrw() : null),
                safeInt(budget != null ? budget.getItemCount() : null),
                safeInt(records != null ? records.getRecordCount() : null),
                safeInt(records != null ? records.getMemoryRecordCount() : null),
                safeInt(media != null ? media.getMediaItemCount() : null),
                safeInt(routes != null ? routes.getRouteSegmentCount() : null),
                routes != null && routes.getTotalDistanceKm() != null
                        ? routes.getTotalDistanceKm().setScale(3, RoundingMode.HALF_UP)
                        : ZERO_DISTANCE,
                safeInt(routes != null ? routes.getTotalDurationMinutes() : null),
                safeInt(routes != null ? routes.getTotalStepCount() : null)
        );
    }

    private TravelPlanSummaryResponse toPlanSummary(
            TravelPlan plan,
            BigDecimal plannedTotalKrw,
            BigDecimal actualTotalKrw,
            int budgetItemCount,
            int recordCount,
            int memoryRecordCount,
            int mediaItemCount,
            int routeSegmentCount,
            BigDecimal totalDistanceKm,
            int totalDurationMinutes,
            int totalStepCount
    ) {
        return new TravelPlanSummaryResponse(
                plan.getId(),
                plan.getName(),
                plan.getDestination(),
                plan.getStartDate(),
                plan.getEndDate(),
                plan.getHomeCurrency(),
                plan.getHeadCount(),
                resolvePlanStatus(plan.getStatus()).name(),
                normalizeColorHex(plan.getColorHex()),
                plan.getMemo(),
                plannedTotalKrw,
                actualTotalKrw,
                budgetItemCount,
                recordCount,
                memoryRecordCount,
                mediaItemCount,
                routeSegmentCount,
                totalDistanceKm,
                totalDurationMinutes,
                totalStepCount
        );
    }

    private int safeInt(Long value) {
        if (value == null || value <= 0L) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value.intValue();
    }

    private TravelPlanDetailResponse toPlanDetail(
            TravelPlan plan,
            List<TravelBudgetItem> budgetItems,
            List<TravelExpenseRecord> records,
            List<TravelExpenseRecord> memoryRecords,
            List<TravelMediaAsset> mediaItems,
            List<TravelRouteSegment> routeSegments
    ) {
        return TravelPlanDetailMapper.response(TravelPlanDetailMapper.snapshot(
                plan, budgetItems, records, memoryRecords, mediaItems, routeSegments, routePayload),
                id -> "/api/travel/media/" + id + "/content");
    }

    private TravelBudgetItemResponse toBudgetItemResponse(TravelBudgetItem budgetItem) {
        return new TravelBudgetItemResponse(
                budgetItem.getId(),
                budgetItem.getCategory(),
                budgetItem.getTitle(),
                budgetItem.getAmount(),
                budgetItem.getCurrencyCode(),
                budgetItem.getExchangeRateToKrw(),
                budgetItem.getAmountKrw(),
                budgetItem.getMemo(),
                budgetItem.getDisplayOrder()
        );
    }

    private TravelExpenseRecordResponse toExpenseRecordResponse(TravelExpenseRecord record) {
        TravelPlan plan = record.getPlan();
        String colorHex = normalizeColorHex(plan.getColorHex());
        return new TravelExpenseRecordResponse(
                record.getId(),
                plan.getId(),
                plan.getName(),
                colorHex,
                record.getRecordType(),
                record.getExpenseDate(),
                record.getExpenseTime(),
                record.getCategory(),
                record.getTitle(),
                record.getAmount(),
                record.getCurrencyCode(),
                record.getExchangeRateToKrw(),
                record.getAmountKrw(),
                record.getCountry(),
                record.getRegion(),
                record.getPlaceName(),
                record.getLatitude(),
                record.getLongitude(),
                record.getMemo()
        );
    }

    private TravelMemoryRecordResponse toMemoryRecordResponse(TravelExpenseRecord record) {
        TravelPlan plan = record.getPlan();
        String colorHex = normalizeColorHex(plan.getColorHex());
        return new TravelMemoryRecordResponse(
                record.getId(),
                plan.getId(),
                plan.getName(),
                colorHex,
                record.getRecordType(),
                record.getExpenseDate(),
                record.getExpenseTime(),
                record.getCategory(),
                record.getTitle(),
                record.getCountry(),
                record.getRegion(),
                record.getPlaceName(),
                record.getLatitude(),
                record.getLongitude(),
                Boolean.TRUE.equals(record.getSharedWithCommunity()),
                record.getMemo()
        );
    }

    private TravelRouteSegmentResponse toRouteSegmentResponse(TravelRouteSegment routeSegment) {
        return TravelRouteMapper.response(TravelRouteMapper.result(routeSegment,routePayload,false));
    }

    private TravelMediaResponse toMediaResponse(TravelMediaAsset mediaAsset) {
        return toMediaResponse(mediaAsset, "/api/travel/media/" + mediaAsset.getId() + "/content");
    }

    private TravelMediaResponse toMediaResponse(TravelMediaAsset mediaAsset, String contentUrl) {
        return TravelClusterDetailMapper.response(TravelClusterDetailMapper.media(mediaAsset), contentUrl);
    }

    private BigDecimal normalizeAmountKrw(BigDecimal amount) {
        return amount == null ? ZERO : amount.setScale(2, RoundingMode.HALF_UP);
    }

    private <T> BigDecimal sumAmountKrw(Collection<T> items, Function<T, BigDecimal> extractor) {
        return items.stream()
                .map(extractor)
                .filter(value -> value != null)
                .reduce(ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sumDistanceKm(Collection<TravelRouteSegment> routes) {
        return routes.stream()
                .map(TravelRouteSegment::getDistanceKm)
                .filter(value -> value != null)
                .reduce(ZERO_DISTANCE, BigDecimal::add)
                .setScale(3, RoundingMode.HALF_UP);
    }

    private <T> Integer sumInteger(Collection<T> items, Function<T, Integer> extractor) {
        return items.stream()
                .map(extractor)
                .filter(value -> value != null)
                .reduce(0, Integer::sum);
    }

    private String normalizeColorHex(String value) {
        return TravelPlanPolicy.color(value);
    }

    private TravelPlanStatus resolvePlanStatus(TravelPlanStatus value) {
        return TravelPlanPolicy.status(value);
    }

    private boolean isLedgerRecord(TravelExpenseRecord record) {
        return TravelRecordPolicy.matches(record.getRecordType(), TravelRecordType.LEDGER);
    }

    private boolean isMemoryRecord(TravelExpenseRecord record) {
        return TravelRecordPolicy.matches(record.getRecordType(), TravelRecordType.MEMORY);
    }
}
