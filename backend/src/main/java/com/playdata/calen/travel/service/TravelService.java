package com.playdata.calen.travel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.domain.TravelBudgetItem;
import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.TravelMediaType;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelPlanShare;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.domain.TravelRouteLineStyle;
import com.playdata.calen.travel.domain.TravelRouteSegment;
import com.playdata.calen.travel.domain.TravelRouteSourceType;
import com.playdata.calen.travel.domain.TravelRouteTransportMode;
import com.playdata.calen.travel.dto.TravelBudgetItemRequest;
import com.playdata.calen.travel.dto.TravelBudgetItemResponse;
import com.playdata.calen.travel.dto.TravelCategoryCatalogResponse;
import com.playdata.calen.travel.dto.TravelCommunityFeedPageResponse;
import com.playdata.calen.travel.dto.TravelCommunityPostResponse;
import com.playdata.calen.travel.dto.TravelExchangeRateResponse;
import com.playdata.calen.travel.dto.TravelExpenseRecordRequest;
import com.playdata.calen.travel.dto.TravelExpenseRecordResponse;
import com.playdata.calen.travel.dto.TravelMediaResponse;
import com.playdata.calen.travel.dto.TravelMediaUploadCompleteRequest;
import com.playdata.calen.travel.dto.TravelMediaUploadPrepareRequest;
import com.playdata.calen.travel.dto.TravelMediaUploadPrepareResponse;
import com.playdata.calen.travel.dto.TravelMediaUploadTargetResponse;
import com.playdata.calen.travel.dto.TravelMyMapMarkerDetailBundleResponse;
import com.playdata.calen.travel.dto.TravelMyMapMarkerDetailResponse;
import com.playdata.calen.travel.dto.TravelMyMapMarkerSummaryResponse;
import com.playdata.calen.travel.dto.TravelMyMapOverviewResponse;
import com.playdata.calen.travel.dto.TravelMyMapPhotoClusterDetailResponse;
import com.playdata.calen.travel.dto.TravelMyMapPhotoClusterSummaryResponse;
import com.playdata.calen.travel.dto.TravelMemoryRecordRequest;
import com.playdata.calen.travel.dto.TravelMemoryRecordResponse;
import com.playdata.calen.travel.dto.TravelPlanDetailResponse;
import com.playdata.calen.travel.dto.TravelPlanRequest;
import com.playdata.calen.travel.dto.TravelPlanShareResponse;
import com.playdata.calen.travel.dto.TravelPlanSummaryResponse;
import com.playdata.calen.travel.dto.TravelPortfolioResponse;
import com.playdata.calen.travel.dto.TravelRoutePointRequest;
import com.playdata.calen.travel.dto.TravelRoutePointResponse;
import com.playdata.calen.travel.dto.TravelRouteSegmentRequest;
import com.playdata.calen.travel.dto.TravelRouteSegmentResponse;
import com.playdata.calen.travel.dto.TravelSharedExhibitDetailResponse;
import com.playdata.calen.travel.dto.TravelSharedExhibitPageResponse;
import com.playdata.calen.travel.dto.TravelSharedExhibitSummaryResponse;
import com.playdata.calen.travel.repository.TravelBudgetItemRepository;
import com.playdata.calen.travel.repository.TravelExpenseRecordRepository;
import com.playdata.calen.travel.repository.TravelMediaAssetRepository;
import com.playdata.calen.travel.repository.TravelPlanRepository;
import com.playdata.calen.travel.repository.TravelPlanShareRepository;
import com.playdata.calen.travel.repository.TravelRouteSegmentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TravelService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal ZERO_DISTANCE = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
    private static final String KRW = "KRW";
    private static final String DEFAULT_TRAVEL_COLOR = "#3182F6";
    private static final int DEFAULT_LIST_PAGE_SIZE = 10;
    private static final int MAX_LIST_PAGE_SIZE = 20;
    private static final int MAX_STORED_ROUTE_POINTS = 900;
    private static final int MY_MAP_NEARBY_MARKER_COUNT = 10;
    private static final int DEFAULT_ROUTE_PATH_CHAR_BUDGET = 12000;
    private static final int MIN_ROUTE_PATH_CHAR_BUDGET = 220;
    private static final String PLANS_CACHE_KEY_PREFIX = "travel:plans:";
    private static final String PORTFOLIO_CACHE_KEY_PREFIX = "travel:portfolio:";
    private static final String MY_MAP_OVERVIEW_CACHE_KEY_PREFIX = "travel:mymap:overview:";
    private static final String ROUTE_PATH_FORMAT_POLYLINE6 = "POLYLINE6";
    private static final int ROUTE_PATH_PRECISION = 6;
    private static final String ROUTE_POINT_TYPE_ROUTE = "ROUTE";
    private static final String ROUTE_POINT_TYPE_MEMORY = "MEMORY";
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
    private final TravelPlanShareRepository travelPlanShareRepository;
    private final ExchangeRateService exchangeRateService;
    private final TravelMediaStorageService travelMediaStorageService;
    private final TravelPhotoGpsMetadataService travelPhotoGpsMetadataService;
    private final TravelPhotoClusterService travelPhotoClusterService;
    private final TravelPublicMediaTokenService travelPublicMediaTokenService;
    private final RedisCacheService redisCacheService;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    @Value("${app.travel.summary-cache-ttl-seconds:60}")
    private long travelSummaryCacheTtlSeconds;

    private volatile Integer routePathCharBudget;

    private record RoutePathPayload(
            String format,
            String encodedPath
    ) {
    }

    private record RouteGpxFile(
            String originalFileName,
            String storagePath,
            String contentType,
            long fileSize
    ) {
    }

    public List<TravelPlanSummaryResponse> getPlans(Long userId) {
        appUserService.getRequiredUser(userId);

        List<TravelPlanSummaryResponse> cachedPlans = redisCacheService.get(buildPlansCacheKey(userId), TRAVEL_PLAN_SUMMARIES_TYPE);
        if (cachedPlans != null) {
            return cachedPlans;
        }

        List<TravelPlan> plans = travelPlanRepository.findAllByOwnerIdOrderByStartDateDescIdDesc(userId);
        Map<Long, List<TravelBudgetItem>> budgetItemsByPlan = travelBudgetItemRepository.findAllByPlanOwnerId(userId).stream()
                .collect(Collectors.groupingBy(item -> item.getPlan().getId()));
        List<TravelExpenseRecord> allRecords = travelExpenseRecordRepository.findAllByPlanOwnerId(userId);
        Map<Long, List<TravelExpenseRecord>> ledgerRecordsByPlan = allRecords.stream()
                .filter(this::isLedgerRecord)
                .collect(Collectors.groupingBy(item -> item.getPlan().getId()));
        Map<Long, List<TravelExpenseRecord>> memoryRecordsByPlan = allRecords.stream()
                .filter(this::isMemoryRecord)
                .collect(Collectors.groupingBy(item -> item.getPlan().getId()));
        Map<Long, List<TravelMediaAsset>> mediaByPlan = travelMediaAssetRepository.findAllByPlanOwnerIdOrderByUploadedAtDescIdDesc(userId).stream()
                .collect(Collectors.groupingBy(item -> item.getPlan().getId()));
        Map<Long, List<TravelRouteSegment>> routesByPlan = travelRouteSegmentRepository.findAllByPlanOwnerIdOrderByRouteDateDescIdDesc(userId).stream()
                .collect(Collectors.groupingBy(item -> item.getPlan().getId()));

        List<TravelPlanSummaryResponse> summaries = plans.stream()
                .map(plan -> toPlanSummary(
                        plan,
                        budgetItemsByPlan.getOrDefault(plan.getId(), Collections.emptyList()),
                        ledgerRecordsByPlan.getOrDefault(plan.getId(), Collections.emptyList()),
                        memoryRecordsByPlan.getOrDefault(plan.getId(), Collections.emptyList()),
                        mediaByPlan.getOrDefault(plan.getId(), Collections.emptyList()),
                        routesByPlan.getOrDefault(plan.getId(), Collections.emptyList())
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

    public TravelMyMapOverviewResponse getMyMapOverview(Long userId) {
        appUserService.getRequiredUser(userId);

        TravelMyMapOverviewResponse cachedOverview = redisCacheService.get(buildMyMapOverviewCacheKey(userId), TravelMyMapOverviewResponse.class);
        if (cachedOverview != null) {
            return cachedOverview;
        }

        List<TravelPlan> plans = travelPlanRepository.findAllByOwnerIdOrderByStartDateDescIdDesc(userId);
        List<TravelExpenseRecord> markers = travelExpenseRecordRepository.findAllByPlanOwnerIdAndRecordType(userId, TravelRecordType.MEMORY).stream()
                .filter(this::hasCoordinates)
                .sorted(RECORD_ORDER)
                .toList();
        List<TravelMediaAsset> photoMediaItems = getMyMapPhotoMediaItems(userId);
        List<TravelPhotoClusterService.PhotoCluster> photoClusters = buildMyMapPhotoClusters(photoMediaItems);
        List<TravelRouteSegment> routeSegments = travelRouteSegmentRepository.findAllByPlanOwnerIdOrderByRouteDateDescIdDesc(userId).stream()
                .sorted(ROUTE_ORDER)
                .toList();

        TravelMyMapOverviewResponse response = new TravelMyMapOverviewResponse(
                plans.size(),
                markers.size(),
                photoMediaItems.size(),
                photoClusters.size(),
                routeSegments.size(),
                sumDistanceKm(routeSegments),
                markers.stream().map(this::toMyMapMarkerSummaryResponse).toList(),
                photoClusters.stream().map(this::toMyMapPhotoClusterSummaryResponse).toList(),
                routeSegments.stream().map(this::toMyMapRouteResponse).toList()
        );
        cacheTravelSummary(buildMyMapOverviewCacheKey(userId), response);
        return response;
    }

    public TravelMyMapMarkerDetailBundleResponse getMyMapMarkerDetailBundle(Long userId, Long markerId) {
        appUserService.getRequiredUser(userId);

        TravelExpenseRecord selectedRecord = travelExpenseRecordRepository.findByIdAndPlanOwnerIdAndRecordType(markerId, userId, TravelRecordType.MEMORY)
                .orElseThrow(() -> new NotFoundException("Travel memory not found."));

        if (!hasCoordinates(selectedRecord)) {
            throw new BadRequestException("Location coordinates are required for map detail loading.");
        }

        List<TravelExpenseRecord> nearbyRecords = travelExpenseRecordRepository.findAllByPlanOwnerIdAndRecordType(userId, TravelRecordType.MEMORY).stream()
                .filter(this::hasCoordinates)
                .filter(record -> !record.getId().equals(selectedRecord.getId()))
                .sorted(Comparator.comparingDouble(record -> calculateDistanceMeters(selectedRecord, record)))
                .limit(MY_MAP_NEARBY_MARKER_COUNT)
                .toList();

        List<TravelExpenseRecord> bundleRecords = new ArrayList<>();
        bundleRecords.add(selectedRecord);
        bundleRecords.addAll(nearbyRecords);

        List<Long> recordIds = bundleRecords.stream()
                .map(TravelExpenseRecord::getId)
                .toList();
        Map<Long, List<TravelMediaAsset>> mediaByRecord = travelMediaAssetRepository.findAllByRecordIdInOrderByUploadedAtDescIdDesc(recordIds).stream()
                .filter(asset -> asset.getMediaType() == TravelMediaType.PHOTO)
                .collect(Collectors.groupingBy(asset -> asset.getRecord().getId()));

        return new TravelMyMapMarkerDetailBundleResponse(
                selectedRecord.getId(),
                bundleRecords.stream()
                        .map(record -> toMyMapMarkerDetailResponse(record, mediaByRecord.getOrDefault(record.getId(), Collections.emptyList())))
                        .toList()
        );
    }

    public TravelMyMapPhotoClusterDetailResponse getMyMapPhotoClusterDetail(Long userId, Long clusterId) {
        appUserService.getRequiredUser(userId);

        TravelPhotoClusterService.PhotoCluster cluster = findRequiredMyMapPhotoCluster(userId, clusterId);
        List<TravelMediaAsset> photoMediaItems = getMyMapPhotoMediaItems(userId);
        Map<Long, TravelMediaAsset> mediaAssetById = photoMediaItems.stream()
                .collect(Collectors.toMap(TravelMediaAsset::getId, Function.identity()));

        return toMyMapPhotoClusterDetailResponse(cluster, mediaAssetById);
    }

    @Transactional
    public TravelMyMapPhotoClusterDetailResponse updateMyMapPhotoClusterRepresentative(Long userId, Long clusterId, Long mediaId) {
        appUserService.getRequiredUser(userId);

        TravelPhotoClusterService.PhotoCluster cluster = findRequiredMyMapPhotoCluster(userId, clusterId);
        if (!cluster.memberMediaIds().contains(mediaId)) {
            throw new BadRequestException("Selected photo is not part of this cluster.");
        }

        List<TravelMediaAsset> photoMediaItems = getMyMapPhotoMediaItems(userId);
        Map<Long, TravelMediaAsset> mediaAssetById = photoMediaItems.stream()
                .collect(Collectors.toMap(TravelMediaAsset::getId, Function.identity()));

        cluster.members().forEach(member -> {
            TravelMediaAsset mediaAsset = mediaAssetById.get(member.mediaId());
            if (mediaAsset != null) {
                mediaAsset.setRepresentativeOverride(member.mediaId().equals(mediaId));
            }
        });

        travelMediaAssetRepository.saveAll(
                cluster.members().stream()
                        .map(member -> mediaAssetById.get(member.mediaId()))
                        .filter(java.util.Objects::nonNull)
                        .toList()
        );
        invalidateTravelSummaryCaches(userId);

        TravelPhotoClusterService.PhotoCluster refreshedCluster = findRequiredMyMapPhotoCluster(userId, clusterId);
        return toMyMapPhotoClusterDetailResponse(refreshedCluster, mediaAssetById);
    }

    public TravelCategoryCatalogResponse getCategoryCatalog(Long userId) {
        appUserService.getRequiredUser(userId);

        List<TravelBudgetItem> budgetItems = travelBudgetItemRepository.findAllByPlanOwnerId(userId);
        List<TravelExpenseRecord> records = travelExpenseRecordRepository.findAllByPlanOwnerId(userId);

        List<String> budgetCategories = distinctSorted(
                DEFAULT_BUDGET_CATEGORIES.stream(),
                budgetItems.stream().map(TravelBudgetItem::getCategory)
        );
        List<String> expenseCategories = distinctSorted(
                DEFAULT_BUDGET_CATEGORIES.stream(),
                records.stream().filter(this::isLedgerRecord).map(TravelExpenseRecord::getCategory)
        );
        List<String> memoryCategories = distinctSorted(
                DEFAULT_MEMORY_CATEGORIES.stream(),
                records.stream().filter(this::isMemoryRecord).map(TravelExpenseRecord::getCategory)
        );

        return new TravelCategoryCatalogResponse(
                List.of(TravelPlanStatus.PLANNED.name(), TravelPlanStatus.COMPLETED.name(), TravelPlanStatus.SAMPLE.name()),
                budgetCategories,
                expenseCategories,
                memoryCategories,
                distinctSorted(records.stream().map(TravelExpenseRecord::getCountry)),
                distinctSorted(records.stream().map(TravelExpenseRecord::getRegion)),
                distinctSorted(records.stream().map(TravelExpenseRecord::getPlaceName))
        );
    }

    public TravelCommunityFeedPageResponse getCommunityFeed(Long userId, Integer page, Integer size) {
        appUserService.getRequiredUser(userId);
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);

        Page<TravelExpenseRecord> sharedMemoriesPage = travelExpenseRecordRepository
                .findCommunityMemoryPage(
                        TravelRecordType.MEMORY,
                        TravelMediaType.PHOTO,
                        PageRequest.of(normalizedPage, normalizedSize)
                );

        List<TravelExpenseRecord> sharedMemories = sharedMemoriesPage.getContent()
                .stream()
                .filter(this::isMemoryRecord)
                .sorted(RECORD_ORDER)
                .toList();

        if (sharedMemories.isEmpty()) {
            return new TravelCommunityFeedPageResponse(
                    Collections.emptyList(),
                    normalizedPage,
                    normalizedSize,
                    sharedMemoriesPage.getTotalElements(),
                    sharedMemoriesPage.getTotalPages()
            );
        }

        List<Long> recordIds = sharedMemories.stream().map(TravelExpenseRecord::getId).toList();
        Map<Long, List<TravelMediaAsset>> photosByRecord = travelMediaAssetRepository.findAllByRecordIdInOrderByUploadedAtDescIdDesc(recordIds).stream()
                .filter(asset -> asset.getMediaType() == TravelMediaType.PHOTO)
                .collect(Collectors.groupingBy(asset -> asset.getRecord().getId()));

        List<TravelCommunityPostResponse> items = sharedMemories.stream()
                .filter(record -> !photosByRecord.getOrDefault(record.getId(), Collections.emptyList()).isEmpty())
                .map(record -> toCommunityPostResponse(record, photosByRecord.get(record.getId())))
                .toList();

        return new TravelCommunityFeedPageResponse(
                items,
                normalizedPage,
                normalizedSize,
                sharedMemoriesPage.getTotalElements(),
                sharedMemoriesPage.getTotalPages()
        );
    }

    public TravelPlanShareResponse shareCompletedPlan(Long userId, Long planId, String recipientLoginIdRaw) {
        AppUser sharer = appUserService.getRequiredUser(userId);
        TravelPlan plan = getRequiredPlan(userId, planId);
        ensureCompletedPlan(plan);

        String recipientLoginId = trimToNull(recipientLoginIdRaw);
        if (recipientLoginId == null) {
            throw new BadRequestException("공유할 사용자 아이디를 입력해주세요.");
        }

        AppUser recipient = appUserService.findActiveUserByLoginId(recipientLoginId)
                .orElseThrow(() -> new NotFoundException("공유할 사용자를 찾을 수 없습니다."));

        if (recipient.getId().equals(sharer.getId())) {
            throw new BadRequestException("자기 자신에게는 여행 전시를 공유할 수 없습니다.");
        }

        TravelPlanShare share = travelPlanShareRepository.findByPlanIdAndRecipientId(planId, recipient.getId())
                .orElseGet(() -> {
                    TravelPlanShare created = new TravelPlanShare();
                    created.setPlan(plan);
                    created.setSharedBy(sharer);
                    created.setRecipient(recipient);
                    created.setCreatedAt(java.time.LocalDateTime.now());
                    return travelPlanShareRepository.save(created);
                });

        return toTravelPlanShareResponse(share);
    }

    public TravelSharedExhibitPageResponse getSharedExhibits(Long userId, Integer page, Integer size) {
        appUserService.getRequiredUser(userId);
        int normalizedPage = normalizePage(page);
        int normalizedSize = normalizeSize(size);
        Page<TravelPlanShare> sharePage = travelPlanShareRepository.findAllByRecipientIdOrderByCreatedAtDescIdDesc(
                userId,
                PageRequest.of(normalizedPage, normalizedSize)
        );
        List<TravelSharedExhibitSummaryResponse> items = sharePage.getContent().stream()
                .map(this::toSharedExhibitSummaryResponse)
                .toList();
        return new TravelSharedExhibitPageResponse(
                items,
                normalizedPage,
                normalizedSize,
                sharePage.getTotalElements(),
                sharePage.getTotalPages()
        );
    }

    public TravelSharedExhibitDetailResponse getSharedExhibit(Long userId, Long shareId) {
        appUserService.getRequiredUser(userId);
        TravelPlanShare share = getRequiredShare(userId, shareId);
        TravelPlan plan = share.getPlan();
        Long ownerId = plan.getOwner().getId();

        TravelPlanDetailResponse detail = toPlanDetail(
                plan,
                getPlanBudgetItems(ownerId, plan.getId()),
                getPlanRecords(ownerId, plan.getId()),
                getPlanMemoryRecords(ownerId, plan.getId()),
                getPlanMedia(ownerId, plan.getId()),
                getPlanRoutes(ownerId, plan.getId()),
                mediaAsset -> "/api/travel/shared-exhibits/" + share.getId() + "/media/" + mediaAsset.getId() + "/content"
        );

        return new TravelSharedExhibitDetailResponse(
                share.getId(),
                share.getSharedBy().getLoginId(),
                share.getSharedBy().getDisplayName(),
                share.getCreatedAt(),
                detail
        );
    }

    @Transactional
    public TravelPlanSummaryResponse createPlan(Long userId, TravelPlanRequest request) {
        TravelPlan plan = new TravelPlan();
        plan.setOwner(appUserService.getRequiredUser(userId));
        applyPlanRequest(plan, request);
        TravelPlan savedPlan = travelPlanRepository.save(plan);
        TravelPlanSummaryResponse response = toPlanSummary(
                savedPlan,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        invalidateTravelSummaryCaches(userId);
        return response;
    }

    @Transactional
    public TravelPlanSummaryResponse updatePlan(Long userId, Long planId, TravelPlanRequest request) {
        TravelPlan plan = getRequiredPlan(userId, planId);
        applyPlanRequest(plan, request);
        TravelPlanSummaryResponse response = toPlanSummary(
                plan,
                getPlanBudgetItems(userId, planId),
                getPlanRecords(userId, planId),
                getPlanMemoryRecords(userId, planId),
                getPlanMedia(userId, planId),
                getPlanRoutes(userId, planId)
        );
        invalidateTravelSummaryCaches(userId);
        return response;
    }

    @Transactional
    public void deletePlan(Long userId, Long planId) {
        TravelPlan plan = getRequiredPlan(userId, planId);
        deleteMediaAssets(getPlanMedia(userId, planId));
        travelMediaAssetRepository.deleteAllByPlanId(plan.getId());
        deleteRouteAssets(getPlanRoutes(userId, planId));
        travelRouteSegmentRepository.deleteAllByPlanId(plan.getId());
        travelPlanShareRepository.deleteAllByPlanId(plan.getId());
        travelBudgetItemRepository.deleteAllByPlanId(plan.getId());
        travelExpenseRecordRepository.deleteAllByPlanId(plan.getId());
        travelPlanRepository.delete(plan);
        invalidateTravelSummaryCaches(userId);
    }

    @Transactional
    public TravelBudgetItemResponse createBudgetItem(Long userId, Long planId, TravelBudgetItemRequest request) {
        TravelPlan plan = getRequiredPlan(userId, planId);
        TravelBudgetItem budgetItem = new TravelBudgetItem();
        budgetItem.setPlan(plan);
        applyBudgetItemRequest(budgetItem, request);
        TravelBudgetItemResponse response = toBudgetItemResponse(travelBudgetItemRepository.save(budgetItem));
        invalidateTravelSummaryCaches(userId);
        return response;
    }

    @Transactional
    public TravelBudgetItemResponse updateBudgetItem(Long userId, Long itemId, TravelBudgetItemRequest request) {
        TravelBudgetItem budgetItem = travelBudgetItemRepository.findByIdAndPlanOwnerId(itemId, userId)
                .orElseThrow(() -> new NotFoundException("Travel budget item not found."));
        applyBudgetItemRequest(budgetItem, request);
        TravelBudgetItemResponse response = toBudgetItemResponse(budgetItem);
        invalidateTravelSummaryCaches(userId);
        return response;
    }

    @Transactional
    public void deleteBudgetItem(Long userId, Long itemId) {
        TravelBudgetItem budgetItem = travelBudgetItemRepository.findByIdAndPlanOwnerId(itemId, userId)
                .orElseThrow(() -> new NotFoundException("Travel budget item not found."));
        travelBudgetItemRepository.delete(budgetItem);
        invalidateTravelSummaryCaches(userId);
    }

    @Transactional
    public TravelExpenseRecordResponse createExpenseRecord(Long userId, Long planId, TravelExpenseRecordRequest request) {
        TravelPlan plan = getRequiredPlan(userId, planId);
        TravelExpenseRecord record = new TravelExpenseRecord();
        record.setPlan(plan);
        applyExpenseRecordRequest(record, request, TravelRecordType.LEDGER);
        TravelExpenseRecordResponse response = toExpenseRecordResponse(travelExpenseRecordRepository.save(record));
        invalidateTravelSummaryCaches(userId);
        return response;
    }

    @Transactional
    public TravelExpenseRecordResponse updateExpenseRecord(Long userId, Long recordId, TravelExpenseRecordRequest request) {
        TravelExpenseRecord record = getRequiredRecord(userId, recordId, TravelRecordType.LEDGER, "Travel ledger record not found.");
        applyExpenseRecordRequest(record, request, TravelRecordType.LEDGER);
        TravelExpenseRecordResponse response = toExpenseRecordResponse(record);
        invalidateTravelSummaryCaches(userId);
        return response;
    }

    @Transactional
    public void deleteExpenseRecord(Long userId, Long recordId) {
        TravelExpenseRecord record = getRequiredRecord(userId, recordId, TravelRecordType.LEDGER, "Travel ledger record not found.");
        deleteRecord(record, userId);
        invalidateTravelSummaryCaches(userId);
    }

    @Transactional
    public TravelMemoryRecordResponse createMemoryRecord(Long userId, Long planId, TravelMemoryRecordRequest request) {
        TravelPlan plan = getRequiredPlan(userId, planId);
        TravelExpenseRecord record = new TravelExpenseRecord();
        record.setPlan(plan);
        applyMemoryRecordRequest(record, request);
        TravelMemoryRecordResponse response = toMemoryRecordResponse(travelExpenseRecordRepository.save(record));
        invalidateTravelSummaryCaches(userId);
        return response;
    }

    @Transactional
    public TravelMemoryRecordResponse updateMemoryRecord(Long userId, Long memoryId, TravelMemoryRecordRequest request) {
        TravelExpenseRecord record = getRequiredRecord(userId, memoryId, TravelRecordType.MEMORY, "Travel memory not found.");
        applyMemoryRecordRequest(record, request);
        TravelMemoryRecordResponse response = toMemoryRecordResponse(record);
        invalidateTravelSummaryCaches(userId);
        return response;
    }

    @Transactional
    public void deleteMemoryRecord(Long userId, Long memoryId) {
        TravelExpenseRecord record = getRequiredRecord(userId, memoryId, TravelRecordType.MEMORY, "Travel memory not found.");
        deleteRecord(record, userId);
        invalidateTravelSummaryCaches(userId);
    }

    @Transactional
    public TravelRouteSegmentResponse createRouteSegment(Long userId, Long planId, TravelRouteSegmentRequest request) {
        TravelPlan plan = getRequiredPlan(userId, planId);
        TravelRouteSegment routeSegment = new TravelRouteSegment();
        routeSegment.setPlan(plan);
        applyRouteSegmentRequest(routeSegment, request);
        TravelRouteSegmentResponse response = toRouteSegmentResponse(travelRouteSegmentRepository.save(routeSegment));
        invalidateTravelSummaryCaches(userId);
        return response;
    }

    @Transactional
    public TravelRouteSegmentResponse updateRouteSegment(Long userId, Long routeId, TravelRouteSegmentRequest request) {
        TravelRouteSegment routeSegment = travelRouteSegmentRepository.findByIdAndPlanOwnerId(routeId, userId)
                .orElseThrow(() -> new NotFoundException("Travel route not found."));
        applyRouteSegmentRequest(routeSegment, request);
        TravelRouteSegmentResponse response = toRouteSegmentResponse(routeSegment);
        invalidateTravelSummaryCaches(userId);
        return response;
    }

    @Transactional
    public TravelRouteSegmentResponse uploadRouteGpxFiles(Long userId, Long routeId, List<MultipartFile> files) {
        TravelRouteSegment routeSegment = travelRouteSegmentRepository.findByIdAndPlanOwnerId(routeId, userId)
                .orElseThrow(() -> new NotFoundException("Travel route not found."));
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("Select at least one GPX file.");
        }

        deleteRouteGpxFilesQuietly(routeSegment);

        List<RouteGpxFile> storedFiles = new ArrayList<>();
        for (MultipartFile file : files) {
            TravelMediaStorageService.StoredTravelMedia storedFile = travelMediaStorageService.storeRouteGpx(
                    userId,
                    routeSegment.getPlan().getId(),
                    routeSegment.getId(),
                    file
            );
            storedFiles.add(new RouteGpxFile(
                    storedFile.originalFileName(),
                    storedFile.storagePath(),
                    storedFile.contentType(),
                    storedFile.fileSize()
            ));
        }

        routeSegment.setSourceType(TravelRouteSourceType.GPX);
        routeSegment.setGpxFilesJson(serializeRouteGpxFiles(storedFiles));
        TravelRouteSegmentResponse response = toRouteSegmentResponse(routeSegment);
        invalidateTravelSummaryCaches(userId);
        return response;
    }

    @Transactional
    public void deleteRouteSegment(Long userId, Long routeId) {
        TravelRouteSegment routeSegment = travelRouteSegmentRepository.findByIdAndPlanOwnerId(routeId, userId)
                .orElseThrow(() -> new NotFoundException("Travel route not found."));
        deleteRouteGpxFilesQuietly(routeSegment);
        travelRouteSegmentRepository.delete(routeSegment);
        invalidateTravelSummaryCaches(userId);
    }

    @Transactional
    public List<TravelMediaResponse> uploadRecordMedia(
            Long userId,
            Long recordId,
            TravelMediaType mediaType,
            String caption,
            List<MultipartFile> files
    ) {
        return uploadRecordMediaInternal(userId, recordId, TravelRecordType.LEDGER, mediaType, caption, files);
    }

    public TravelMediaUploadPrepareResponse prepareRecordMediaUpload(
            Long userId,
            Long recordId,
            TravelMediaUploadPrepareRequest request
    ) {
        return prepareMediaUploadInternal(userId, recordId, TravelRecordType.LEDGER, request);
    }

    @Transactional
    public List<TravelMediaResponse> completeRecordMediaUpload(
            Long userId,
            Long recordId,
            TravelMediaUploadCompleteRequest request
    ) {
        return completeMediaUploadInternal(userId, recordId, TravelRecordType.LEDGER, request.mediaType(), request);
    }

    @Transactional
    public List<TravelMediaResponse> uploadMemoryMedia(
            Long userId,
            Long memoryId,
            String caption,
            List<MultipartFile> files
    ) {
        return uploadRecordMediaInternal(userId, memoryId, TravelRecordType.MEMORY, TravelMediaType.PHOTO, caption, files);
    }

    public TravelMediaUploadPrepareResponse prepareMemoryMediaUpload(
            Long userId,
            Long memoryId,
            TravelMediaUploadPrepareRequest request
    ) {
        return prepareMediaUploadInternal(userId, memoryId, TravelRecordType.MEMORY, request);
    }

    @Transactional
    public List<TravelMediaResponse> completeMemoryMediaUpload(
            Long userId,
            Long memoryId,
            TravelMediaUploadCompleteRequest request
    ) {
        return completeMediaUploadInternal(userId, memoryId, TravelRecordType.MEMORY, TravelMediaType.PHOTO, request);
    }

    @Transactional
    public void deleteMedia(Long userId, Long mediaId) {
        TravelMediaAsset mediaAsset = travelMediaAssetRepository.findByIdAndPlanOwnerId(mediaId, userId)
                .orElseThrow(() -> new NotFoundException("Uploaded file not found."));
        travelMediaStorageService.deleteImageWithThumbnailsQuietly(mediaAsset.getStoragePath(), mediaAsset.getContentType());
        travelMediaAssetRepository.delete(mediaAsset);
        invalidateTravelSummaryCaches(userId);
    }

    public MediaDownload getMediaDownload(Long userId, Long mediaId) {
        TravelMediaAsset mediaAsset = travelMediaAssetRepository.findByIdAndPlanOwnerId(mediaId, userId)
                .orElseThrow(() -> new NotFoundException("Uploaded file not found."));
        return new MediaDownload(mediaAsset.getStoragePath(), mediaAsset.getContentType(), mediaAsset.getOriginalFileName());
    }

    public MediaDownload getSharedMediaDownload(Long mediaId, String token) {
        if (!travelPublicMediaTokenService.matches(mediaId, token)) {
            throw new NotFoundException("Shared media not found.");
        }
        TravelMediaAsset mediaAsset = travelMediaAssetRepository.findById(mediaId)
                .orElseThrow(() -> new NotFoundException("Shared media not found."));
        TravelExpenseRecord record = mediaAsset.getRecord();
        if (!isMemoryRecord(record) || !Boolean.TRUE.equals(record.getSharedWithCommunity()) || mediaAsset.getMediaType() != TravelMediaType.PHOTO) {
            throw new NotFoundException("Shared media not found.");
        }
        return new MediaDownload(mediaAsset.getStoragePath(), mediaAsset.getContentType(), mediaAsset.getOriginalFileName());
    }

    public MediaDownload getSharedExhibitMediaDownload(Long userId, Long shareId, Long mediaId) {
        appUserService.getRequiredUser(userId);
        TravelPlanShare share = getRequiredShare(userId, shareId);
        TravelMediaAsset mediaAsset = travelMediaAssetRepository.findById(mediaId)
                .orElseThrow(() -> new NotFoundException("Shared exhibit media not found."));

        if (!mediaAsset.getPlan().getId().equals(share.getPlan().getId())) {
            throw new NotFoundException("Shared exhibit media not found.");
        }

        return new MediaDownload(mediaAsset.getStoragePath(), mediaAsset.getContentType(), mediaAsset.getOriginalFileName());
    }

    public List<TravelExchangeRateResponse> getExchangeRates(Long userId, String currencies) {
        appUserService.getRequiredUser(userId);
        Set<String> codes = new LinkedHashSet<>();
        if (currencies == null || currencies.isBlank()) {
            codes.addAll(DEFAULT_CURRENCIES);
        } else {
            for (String currency : currencies.split(",")) {
                codes.add(normalizeCurrencyCode(currency));
            }
            codes.add(KRW);
        }
        return exchangeRateService.getLatestRates(codes);
    }

    private List<TravelMediaResponse> uploadRecordMediaInternal(
            Long userId,
            Long recordId,
            TravelRecordType recordType,
            TravelMediaType mediaType,
            String caption,
            List<MultipartFile> files
    ) {
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("Select a file to upload.");
        }

        AppUser currentUser = appUserService.getRequiredUser(userId);
        TravelExpenseRecord record = getRequiredRecord(userId, recordId, recordType, resolveMissingRecordMessage(recordType));
        TravelMediaType resolvedMediaType = resolveMediaType(recordType, mediaType);

        List<TravelMediaResponse> responses = files.stream()
                .filter(file -> file != null && !file.isEmpty())
                .map(file -> {
                    TravelMediaStorageService.StoredTravelMedia storedFile = travelMediaStorageService.store(
                            userId,
                            record.getPlan().getId(),
                            record.getId(),
                            file
                    );
                    return saveMediaAsset(
                            currentUser,
                            record,
                            resolvedMediaType,
                            caption,
                            storedFile,
                            travelPhotoGpsMetadataService.extract(file, storedFile.contentType())
                    );
                })
                .map(this::toMediaResponse)
                .toList();
        if (!responses.isEmpty()) {
            invalidateTravelSummaryCaches(userId);
        }
        return responses;
    }

    private TravelMediaUploadPrepareResponse prepareMediaUploadInternal(
            Long userId,
            Long recordId,
            TravelRecordType recordType,
            TravelMediaUploadPrepareRequest request
    ) {
        TravelExpenseRecord record = getRequiredRecord(userId, recordId, recordType, resolveMissingRecordMessage(recordType));

        try {
            List<TravelMediaStorageService.UploadCandidate> candidates = request.files().stream()
                    .map(file -> new TravelMediaStorageService.UploadCandidate(
                            file.originalFileName(),
                            file.contentType(),
                            file.fileSize()
                    ))
                    .toList();

            List<TravelMediaUploadTargetResponse> uploads = travelMediaStorageService.preparePresignedUploads(
                            userId,
                            record.getPlan().getId(),
                            record.getId(),
                            candidates
                    ).stream()
                    .map(upload -> new TravelMediaUploadTargetResponse(
                            upload.method(),
                            upload.uploadUrl(),
                            upload.objectKey(),
                            upload.storedFileName(),
                            upload.originalFileName(),
                            upload.contentType(),
                            upload.fileSize()
                    ))
                    .toList();

            return new TravelMediaUploadPrepareResponse("PRESIGNED", uploads);
        } catch (BadRequestException exception) {
            return new TravelMediaUploadPrepareResponse("SERVER", Collections.emptyList());
        }
    }

    private List<TravelMediaResponse> completeMediaUploadInternal(
            Long userId,
            Long recordId,
            TravelRecordType recordType,
            TravelMediaType mediaType,
            TravelMediaUploadCompleteRequest request
    ) {
        if (!travelMediaStorageService.supportsPresignedUpload()) {
            throw new BadRequestException("MinIO presigned upload is not available.");
        }

        AppUser currentUser = appUserService.getRequiredUser(userId);
        TravelExpenseRecord record = getRequiredRecord(userId, recordId, recordType, resolveMissingRecordMessage(recordType));
        TravelMediaType resolvedMediaType = resolveMediaType(recordType, mediaType);

        List<TravelMediaResponse> responses = request.files().stream()
                .map(file -> new TravelMediaStorageService.CompletedUpload(
                        file.objectKey(),
                        file.originalFileName(),
                        file.contentType(),
                        file.fileSize()
                ))
                .map(file -> travelMediaStorageService.completePresignedUpload(
                        userId,
                        record.getPlan().getId(),
                        record.getId(),
                        file
                ))
                .map(storedFile -> saveMediaAsset(
                        currentUser,
                        record,
                        resolvedMediaType,
                        request.caption(),
                        storedFile,
                        extractStoredPhotoGps(storedFile)
                ))
                .map(this::toMediaResponse)
                .toList();
        if (!responses.isEmpty()) {
            invalidateTravelSummaryCaches(userId);
        }
        return responses;
    }

    private List<TravelMediaAsset> getMyMapPhotoMediaItems(Long userId) {
        return travelMediaAssetRepository.findAllByPlanOwnerIdOrderByUploadedAtDescIdDesc(userId).stream()
                .filter(asset -> asset.getMediaType() == TravelMediaType.PHOTO)
                .filter(asset -> isMemoryRecord(asset.getRecord()))
                .filter(this::hasClusterCoordinates)
                .toList();
    }

    private List<TravelPhotoClusterService.PhotoCluster> buildMyMapPhotoClusters(List<TravelMediaAsset> photoMediaItems) {
        List<TravelPhotoClusterService.PhotoPoint> points = photoMediaItems.stream()
                .map(this::toPhotoClusterPoint)
                .toList();
        return travelPhotoClusterService.cluster(points);
    }

    private TravelPhotoClusterService.PhotoCluster findRequiredMyMapPhotoCluster(Long userId, Long clusterId) {
        return buildMyMapPhotoClusters(getMyMapPhotoMediaItems(userId)).stream()
                .filter(cluster -> cluster.id().equals(clusterId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Photo cluster not found."));
    }

    private TravelPhotoClusterService.PhotoPoint toPhotoClusterPoint(TravelMediaAsset mediaAsset) {
        TravelExpenseRecord record = mediaAsset.getRecord();
        return new TravelPhotoClusterService.PhotoPoint(
                mediaAsset.getId(),
                record.getId(),
                record.getPlan().getId(),
                record.getPlan().getName(),
                record.getPlan().getColorHex(),
                record.getExpenseDate(),
                record.getExpenseTime(),
                record.getCategory(),
                record.getTitle(),
                record.getCountry(),
                record.getRegion(),
                record.getPlaceName(),
                resolveClusterLatitude(mediaAsset, record),
                resolveClusterLongitude(mediaAsset, record),
                mediaAsset.getUploadedAt(),
                Boolean.TRUE.equals(mediaAsset.getRepresentativeOverride())
        );
    }

    private BigDecimal resolveClusterLatitude(TravelMediaAsset mediaAsset, TravelExpenseRecord record) {
        return mediaAsset.getGpsLatitude() != null ? mediaAsset.getGpsLatitude() : record.getLatitude();
    }

    private BigDecimal resolveClusterLongitude(TravelMediaAsset mediaAsset, TravelExpenseRecord record) {
        return mediaAsset.getGpsLongitude() != null ? mediaAsset.getGpsLongitude() : record.getLongitude();
    }

    private boolean hasClusterCoordinates(TravelMediaAsset mediaAsset) {
        TravelExpenseRecord record = mediaAsset.getRecord();
        return resolveClusterLatitude(mediaAsset, record) != null && resolveClusterLongitude(mediaAsset, record) != null;
    }

    private void cacheTravelSummary(String cacheKey, Object response) {
        Duration ttl = resolveTravelSummaryCacheTtl();
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        redisCacheService.set(cacheKey, response, ttl);
    }

    private void invalidateTravelSummaryCaches(Long userId) {
        redisCacheService.delete(
                buildPlansCacheKey(userId),
                buildPortfolioCacheKey(userId),
                buildMyMapOverviewCacheKey(userId)
        );
    }

    private Duration resolveTravelSummaryCacheTtl() {
        if (travelSummaryCacheTtlSeconds <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofSeconds(travelSummaryCacheTtlSeconds);
    }

    private String buildPlansCacheKey(Long userId) {
        return PLANS_CACHE_KEY_PREFIX + userId;
    }

    private String buildPortfolioCacheKey(Long userId) {
        return PORTFOLIO_CACHE_KEY_PREFIX + userId;
    }

    private String buildMyMapOverviewCacheKey(Long userId) {
        return MY_MAP_OVERVIEW_CACHE_KEY_PREFIX + userId;
    }

    private TravelPlan getRequiredPlan(Long userId, Long planId) {
        return travelPlanRepository.findByIdAndOwnerId(planId, userId)
                .orElseThrow(() -> new NotFoundException("Travel plan not found."));
    }

    private TravelExpenseRecord getRequiredRecord(Long userId, Long recordId, TravelRecordType recordType, String message) {
        TravelExpenseRecord record = travelExpenseRecordRepository.findByIdAndPlanOwnerId(recordId, userId)
                .orElseThrow(() -> new NotFoundException(message));

        if (recordType == TravelRecordType.MEMORY && !isMemoryRecord(record)) {
            throw new NotFoundException(message);
        }
        if (recordType == TravelRecordType.LEDGER && !isLedgerRecord(record)) {
            throw new NotFoundException(message);
        }
        return record;
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

    private int normalizePage(Integer page) {
        return page == null ? 0 : Math.max(page, 0);
    }

    private int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_LIST_PAGE_SIZE;
        }
        return Math.max(1, Math.min(size, MAX_LIST_PAGE_SIZE));
    }

    private void applyPlanRequest(TravelPlan plan, TravelPlanRequest request) {
        if (request.startDate().isAfter(request.endDate())) {
            throw new BadRequestException("Travel start date cannot be after end date.");
        }

        plan.setName(request.name().trim());
        plan.setDestination(trimToNull(request.destination()));
        plan.setStartDate(request.startDate());
        plan.setEndDate(request.endDate());
        plan.setHomeCurrency(normalizeCurrencyCode(request.homeCurrency()));
        plan.setHeadCount(request.headCount());
        plan.setStatus(resolvePlanStatus(request.status()));
        plan.setColorHex(normalizeColorHex(request.colorHex()));
        plan.setMemo(trimToNull(request.memo()));
    }

    private void applyBudgetItemRequest(TravelBudgetItem budgetItem, TravelBudgetItemRequest request) {
        String currencyCode = normalizeCurrencyCode(request.currencyCode());
        BigDecimal rateToKrw = resolveRate(currencyCode, request.exchangeRateToKrw());

        budgetItem.setCategory(request.category().trim());
        budgetItem.setTitle(request.title().trim());
        budgetItem.setAmount(request.amount().setScale(2, RoundingMode.HALF_UP));
        budgetItem.setCurrencyCode(currencyCode);
        budgetItem.setExchangeRateToKrw(rateToKrw);
        budgetItem.setAmountKrw(toKrw(request.amount(), rateToKrw));
        budgetItem.setMemo(trimToNull(request.memo()));
        budgetItem.setDisplayOrder(request.displayOrder() != null ? request.displayOrder() : 0);
    }

    private void applyExpenseRecordRequest(TravelExpenseRecord record, TravelExpenseRecordRequest request, TravelRecordType recordType) {
        String currencyCode = normalizeCurrencyCode(request.currencyCode());
        BigDecimal rateToKrw = resolveRate(currencyCode, request.exchangeRateToKrw());
        validateCoordinates(request.latitude(), request.longitude());

        record.setRecordType(recordType);
        record.setExpenseDate(request.expenseDate());
        record.setExpenseTime(request.expenseTime() != null ? request.expenseTime() : LocalTime.MIDNIGHT);
        record.setCategory(request.category().trim());
        record.setTitle(request.title().trim());
        record.setAmount(request.amount().setScale(2, RoundingMode.HALF_UP));
        record.setCurrencyCode(currencyCode);
        record.setExchangeRateToKrw(rateToKrw);
        record.setAmountKrw(toKrw(request.amount(), rateToKrw));
        record.setCountry(trimToNull(request.country()));
        record.setRegion(trimToNull(request.region()));
        record.setPlaceName(trimToNull(request.placeName()));
        record.setLatitude(normalizeCoordinate(request.latitude(), true));
        record.setLongitude(normalizeCoordinate(request.longitude(), false));
        record.setSharedWithCommunity(false);
        record.setMemo(trimToNull(request.memo()));
    }

    private void applyMemoryRecordRequest(TravelExpenseRecord record, TravelMemoryRecordRequest request) {
        validateCoordinates(request.latitude(), request.longitude());

        record.setRecordType(TravelRecordType.MEMORY);
        record.setExpenseDate(request.memoryDate());
        record.setExpenseTime(request.memoryTime());
        record.setCategory(request.category().trim());
        record.setTitle(request.title().trim());
        record.setAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        record.setCurrencyCode(KRW);
        record.setExchangeRateToKrw(BigDecimal.ONE.setScale(6, RoundingMode.HALF_UP));
        record.setAmountKrw(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        record.setCountry(trimToNull(request.country()));
        record.setRegion(trimToNull(request.region()));
        record.setPlaceName(trimToNull(request.placeName()));
        record.setLatitude(normalizeCoordinate(request.latitude(), true));
        record.setLongitude(normalizeCoordinate(request.longitude(), false));
        record.setSharedWithCommunity(Boolean.TRUE.equals(request.sharedWithCommunity()));
        record.setMemo(trimToNull(request.memo()));
    }

    private void applyRouteSegmentRequest(TravelRouteSegment routeSegment, TravelRouteSegmentRequest request) {
        TravelRouteSourceType sourceType = request.sourceType() != null ? request.sourceType() : TravelRouteSourceType.MANUAL;
        List<TravelRoutePointRequest> normalizedPoints = normalizeRoutePointsForStorage(request.points(), sourceType);

        routeSegment.setRouteDate(request.routeDate());
        routeSegment.setTitle(request.title().trim());
        routeSegment.setTransportMode(request.transportMode() != null ? request.transportMode() : TravelRouteTransportMode.WALK);
        routeSegment.setDistanceKm(request.distanceKm().setScale(3, RoundingMode.HALF_UP));
        routeSegment.setDurationMinutes(request.durationMinutes());
        routeSegment.setStepCount(request.stepCount());
        routeSegment.setSourceType(sourceType);
        routeSegment.setStartPlaceName(trimToNull(request.startPlaceName()));
        routeSegment.setEndPlaceName(trimToNull(request.endPlaceName()));
        routeSegment.setRoutePathJson(serializeRoutePoints(normalizedPoints, sourceType));
        routeSegment.setLineColorHex(resolveRouteLineColor(request.lineColorHex(), routeSegment.getPlan().getColorHex()));
        routeSegment.setLineStyle(request.lineStyle() != null ? request.lineStyle() : TravelRouteLineStyle.SOLID);
        routeSegment.setMemo(trimToNull(request.memo()));
    }

    private TravelPlanSummaryResponse toPlanSummary(
            TravelPlan plan,
            List<TravelBudgetItem> budgetItems,
            List<TravelExpenseRecord> records,
            List<TravelExpenseRecord> memoryRecords,
            List<TravelMediaAsset> mediaItems,
            List<TravelRouteSegment> routeSegments
    ) {
        BigDecimal plannedTotalKrw = sumAmountKrw(budgetItems, TravelBudgetItem::getAmountKrw);
        BigDecimal actualTotalKrw = sumAmountKrw(records, TravelExpenseRecord::getAmountKrw);
        String colorHex = normalizeColorHex(plan.getColorHex());

        return new TravelPlanSummaryResponse(
                plan.getId(),
                plan.getName(),
                plan.getDestination(),
                plan.getStartDate(),
                plan.getEndDate(),
                plan.getHomeCurrency(),
                plan.getHeadCount(),
                resolvePlanStatus(plan.getStatus()).name(),
                colorHex,
                plan.getMemo(),
                plannedTotalKrw,
                actualTotalKrw,
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

    private TravelPlanDetailResponse toPlanDetail(
            TravelPlan plan,
            List<TravelBudgetItem> budgetItems,
            List<TravelExpenseRecord> records,
            List<TravelExpenseRecord> memoryRecords,
            List<TravelMediaAsset> mediaItems,
            List<TravelRouteSegment> routeSegments
    ) {
        return toPlanDetail(
                plan,
                budgetItems,
                records,
                memoryRecords,
                mediaItems,
                routeSegments,
                mediaAsset -> "/api/travel/media/" + mediaAsset.getId() + "/content"
        );
    }

    private TravelPlanDetailResponse toPlanDetail(
            TravelPlan plan,
            List<TravelBudgetItem> budgetItems,
            List<TravelExpenseRecord> records,
            List<TravelExpenseRecord> memoryRecords,
            List<TravelMediaAsset> mediaItems,
            List<TravelRouteSegment> routeSegments,
            Function<TravelMediaAsset, String> mediaContentUrlResolver
    ) {
        TravelPlanSummaryResponse summary = toPlanSummary(plan, budgetItems, records, memoryRecords, mediaItems, routeSegments);

        return new TravelPlanDetailResponse(
                summary.id(),
                summary.name(),
                summary.destination(),
                summary.startDate(),
                summary.endDate(),
                summary.homeCurrency(),
                summary.headCount(),
                summary.status(),
                summary.colorHex(),
                summary.memo(),
                summary.plannedTotalKrw(),
                summary.actualTotalKrw(),
                summary.budgetItemCount(),
                summary.recordCount(),
                summary.memoryRecordCount(),
                summary.mediaItemCount(),
                summary.routeSegmentCount(),
                summary.totalDistanceKm(),
                summary.totalDurationMinutes(),
                summary.totalStepCount(),
                budgetItems.stream().sorted(BUDGET_ITEM_ORDER).map(this::toBudgetItemResponse).toList(),
                records.stream().sorted(RECORD_ORDER).map(this::toExpenseRecordResponse).toList(),
                memoryRecords.stream().sorted(RECORD_ORDER).map(this::toMemoryRecordResponse).toList(),
                mediaItems.stream().sorted(MEDIA_ORDER).map(item -> toMediaResponse(item, mediaContentUrlResolver.apply(item))).toList(),
                routeSegments.stream().sorted(ROUTE_ORDER).map(this::toRouteSegmentResponse).toList()
        );
    }

    private TravelMyMapMarkerSummaryResponse toMyMapMarkerSummaryResponse(TravelExpenseRecord record) {
        return new TravelMyMapMarkerSummaryResponse(
                record.getId(),
                record.getPlan().getId(),
                record.getPlan().getName(),
                normalizeColorHex(record.getPlan().getColorHex()),
                record.getExpenseDate(),
                record.getExpenseTime(),
                record.getCategory(),
                record.getTitle(),
                record.getCountry(),
                record.getRegion(),
                record.getPlaceName(),
                record.getLatitude(),
                record.getLongitude()
        );
    }

    private TravelMyMapMarkerDetailResponse toMyMapMarkerDetailResponse(
            TravelExpenseRecord record,
            List<TravelMediaAsset> mediaItems
    ) {
        List<TravelMediaResponse> mediaResponses = mediaItems.stream()
                .sorted(MEDIA_ORDER)
                .map(this::toMediaResponse)
                .toList();

        return new TravelMyMapMarkerDetailResponse(
                record.getId(),
                record.getPlan().getId(),
                record.getPlan().getName(),
                normalizeColorHex(record.getPlan().getColorHex()),
                record.getExpenseDate(),
                record.getExpenseTime(),
                record.getCategory(),
                record.getTitle(),
                record.getCountry(),
                record.getRegion(),
                record.getPlaceName(),
                record.getLatitude(),
                record.getLongitude(),
                record.getSharedWithCommunity(),
                record.getMemo(),
                mediaResponses.size(),
                mediaResponses
        );
    }

    private TravelMyMapPhotoClusterSummaryResponse toMyMapPhotoClusterSummaryResponse(TravelPhotoClusterService.PhotoCluster cluster) {
        TravelPhotoClusterService.PhotoPoint representative = cluster.representative();
        return new TravelMyMapPhotoClusterSummaryResponse(
                cluster.id(),
                representative.mediaId(),
                representative.recordId(),
                representative.planId(),
                representative.planName(),
                normalizeColorHex(representative.planColorHex()),
                representative.memoryDate(),
                representative.memoryTime(),
                representative.category(),
                representative.title(),
                representative.country(),
                representative.region(),
                representative.placeName(),
                representative.latitude(),
                representative.longitude(),
                cluster.photoCount(),
                cluster.memoryCount(),
                cluster.maxDistanceMeters(),
                representative.representativeOverride(),
                "/api/travel/media/" + representative.mediaId() + "/content"
        );
    }

    private TravelMyMapPhotoClusterDetailResponse toMyMapPhotoClusterDetailResponse(
            TravelPhotoClusterService.PhotoCluster cluster,
            Map<Long, TravelMediaAsset> mediaAssetById
    ) {
        TravelMediaAsset representativeAsset = mediaAssetById.get(cluster.representative().mediaId());
        TravelMediaResponse representativePhoto = representativeAsset != null ? toMediaResponse(representativeAsset) : null;
        List<TravelMediaResponse> photos = cluster.members().stream()
                .map(member -> mediaAssetById.get(member.mediaId()))
                .filter(java.util.Objects::nonNull)
                .map(this::toMediaResponse)
                .toList();

        return new TravelMyMapPhotoClusterDetailResponse(
                cluster.id(),
                cluster.representative().mediaId(),
                cluster.representative().recordId(),
                cluster.representative().latitude(),
                cluster.representative().longitude(),
                cluster.photoCount(),
                cluster.memoryCount(),
                cluster.maxDistanceMeters(),
                cluster.representative().representativeOverride(),
                representativePhoto,
                photos
        );
    }

    private TravelRouteSegmentResponse toMyMapRouteResponse(TravelRouteSegment routeSegment) {
        return new TravelRouteSegmentResponse(
                routeSegment.getId(),
                routeSegment.getPlan().getId(),
                routeSegment.getPlan().getName(),
                normalizeColorHex(routeSegment.getPlan().getColorHex()),
                routeSegment.getRouteDate(),
                routeSegment.getTitle(),
                routeSegment.getTransportMode(),
                routeSegment.getDistanceKm() != null ? routeSegment.getDistanceKm().setScale(3, RoundingMode.HALF_UP) : ZERO_DISTANCE,
                routeSegment.getDurationMinutes(),
                routeSegment.getStepCount(),
                routeSegment.getSourceType(),
                routeSegment.getStartPlaceName(),
                routeSegment.getEndPlaceName(),
                normalizeColorHex(routeSegment.getLineColorHex()),
                routeSegment.getLineStyle() != null ? routeSegment.getLineStyle() : TravelRouteLineStyle.SOLID,
                deserializeRouteGpxFiles(routeSegment.getGpxFilesJson()).stream()
                        .map(RouteGpxFile::originalFileName)
                        .toList(),
                routeSegment.getMemo(),
                deserializeRoutePoints(routeSegment.getRoutePathJson())
        );
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
        TravelPlan plan = routeSegment.getPlan();
        List<RouteGpxFile> routeGpxFiles = deserializeRouteGpxFiles(routeSegment.getGpxFilesJson());
        return new TravelRouteSegmentResponse(
                routeSegment.getId(),
                plan.getId(),
                plan.getName(),
                normalizeColorHex(plan.getColorHex()),
                routeSegment.getRouteDate(),
                routeSegment.getTitle(),
                routeSegment.getTransportMode(),
                routeSegment.getDistanceKm() != null ? routeSegment.getDistanceKm().setScale(3, RoundingMode.HALF_UP) : ZERO_DISTANCE,
                routeSegment.getDurationMinutes() != null ? routeSegment.getDurationMinutes() : 0,
                routeSegment.getStepCount(),
                routeSegment.getSourceType(),
                routeSegment.getStartPlaceName(),
                routeSegment.getEndPlaceName(),
                resolveRouteLineColor(routeSegment.getLineColorHex(), plan.getColorHex()),
                routeSegment.getLineStyle() != null ? routeSegment.getLineStyle() : TravelRouteLineStyle.SOLID,
                routeGpxFiles.stream()
                        .map(RouteGpxFile::originalFileName)
                        .filter(name -> name != null && !name.isBlank())
                        .toList(),
                routeSegment.getMemo(),
                resolveRoutePoints(routeSegment)
        );
    }

    private TravelCommunityPostResponse toCommunityPostResponse(TravelExpenseRecord record, List<TravelMediaAsset> photos) {
        TravelMediaAsset hero = photos.stream().sorted(MEDIA_ORDER).findFirst().orElseThrow();
        TravelPlan plan = record.getPlan();
        return new TravelCommunityPostResponse(
                record.getId(),
                plan.getId(),
                plan.getName(),
                normalizeColorHex(plan.getColorHex()),
                hero.getUploadedBy().getDisplayName(),
                record.getTitle(),
                record.getMemo(),
                record.getExpenseDate(),
                record.getExpenseTime(),
                record.getCountry(),
                record.getRegion(),
                record.getPlaceName(),
                record.getLatitude(),
                record.getLongitude(),
                buildPublicMediaUrl(hero.getId()),
                hero.getCaption(),
                photos.size()
        );
    }

    private String buildPublicMediaUrl(Long mediaId) {
        return "/api/travel/public/media/" + mediaId + "/content?token=" + travelPublicMediaTokenService.issueToken(mediaId);
    }

    private TravelMediaResponse toMediaResponse(TravelMediaAsset mediaAsset) {
        return toMediaResponse(mediaAsset, "/api/travel/media/" + mediaAsset.getId() + "/content");
    }

    private TravelMediaResponse toMediaResponse(TravelMediaAsset mediaAsset, String contentUrl) {
        TravelExpenseRecord record = mediaAsset.getRecord();
        TravelPlan plan = mediaAsset.getPlan();
        String colorHex = normalizeColorHex(plan.getColorHex());
        return new TravelMediaResponse(
                mediaAsset.getId(),
                plan.getId(),
                plan.getName(),
                colorHex,
                record.getId(),
                record.getRecordType(),
                mediaAsset.getMediaType(),
                mediaAsset.getOriginalFileName(),
                mediaAsset.getContentType(),
                mediaAsset.getFileSize(),
                mediaAsset.getCaption(),
                mediaAsset.getUploadedBy().getDisplayName(),
                mediaAsset.getUploadedAt(),
                contentUrl,
                record.getExpenseDate(),
                record.getExpenseTime(),
                record.getTitle(),
                record.getAmount(),
                record.getCurrencyCode(),
                record.getAmountKrw(),
                record.getCountry(),
                record.getRegion(),
                record.getPlaceName(),
                record.getLatitude(),
                record.getLongitude(),
                mediaAsset.getGpsLatitude(),
                mediaAsset.getGpsLongitude(),
                Boolean.TRUE.equals(mediaAsset.getRepresentativeOverride())
        );
    }

    private TravelPlanShare getRequiredShare(Long recipientUserId, Long shareId) {
        return travelPlanShareRepository.findByIdAndRecipientId(shareId, recipientUserId)
                .orElseThrow(() -> new NotFoundException("공유된 여행 전시를 찾을 수 없습니다."));
    }

    private void ensureCompletedPlan(TravelPlan plan) {
        if (resolvePlanStatus(plan.getStatus()) != TravelPlanStatus.COMPLETED) {
            throw new BadRequestException("완성된 여행만 다른 사용자에게 공유할 수 있습니다.");
        }
    }

    private TravelPlanShareResponse toTravelPlanShareResponse(TravelPlanShare share) {
        return new TravelPlanShareResponse(
                share.getId(),
                share.getPlan().getId(),
                share.getPlan().getName(),
                share.getRecipient().getLoginId(),
                share.getRecipient().getDisplayName(),
                share.getCreatedAt()
        );
    }

    private TravelSharedExhibitSummaryResponse toSharedExhibitSummaryResponse(TravelPlanShare share) {
        TravelPlan plan = share.getPlan();
        Long ownerId = plan.getOwner().getId();
        List<TravelExpenseRecord> memoryRecords = getPlanMemoryRecords(ownerId, plan.getId());
        List<TravelMediaAsset> mediaItems = getPlanMedia(ownerId, plan.getId());
        List<TravelRouteSegment> routeSegments = getPlanRoutes(ownerId, plan.getId());

        return new TravelSharedExhibitSummaryResponse(
                share.getId(),
                plan.getId(),
                plan.getName(),
                plan.getDestination(),
                plan.getStartDate(),
                plan.getEndDate(),
                resolvePlanStatus(plan.getStatus()).name(),
                normalizeColorHex(plan.getColorHex()),
                share.getSharedBy().getLoginId(),
                share.getSharedBy().getDisplayName(),
                share.getCreatedAt(),
                memoryRecords.size(),
                routeSegments.size(),
                mediaItems.size()
        );
    }

    private BigDecimal resolveRate(String currencyCode, BigDecimal exchangeRateToKrw) {
        if (KRW.equals(currencyCode)) {
            return BigDecimal.ONE.setScale(6, RoundingMode.HALF_UP);
        }

        if (exchangeRateToKrw != null) {
            if (exchangeRateToKrw.signum() <= 0) {
                throw new BadRequestException("Exchange rate must be greater than 0.");
            }
            return exchangeRateToKrw.setScale(6, RoundingMode.HALF_UP);
        }

        return exchangeRateService.getRequiredRateToKrw(currencyCode).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal toKrw(BigDecimal amount, BigDecimal exchangeRateToKrw) {
        return amount.multiply(exchangeRateToKrw).setScale(2, RoundingMode.HALF_UP);
    }

    private void deleteRecord(TravelExpenseRecord record, Long userId) {
        deleteMediaAssets(travelMediaAssetRepository.findAllByRecordIdAndPlanOwnerIdOrderByUploadedAtDescIdDesc(record.getId(), userId));
        travelMediaAssetRepository.deleteAllByRecordId(record.getId());
        travelExpenseRecordRepository.delete(record);
    }

    private void deleteMediaAssets(List<TravelMediaAsset> mediaAssets) {
        mediaAssets.forEach(asset -> travelMediaStorageService.deleteImageWithThumbnailsQuietly(asset.getStoragePath(), asset.getContentType()));
    }

    private void deleteRouteAssets(List<TravelRouteSegment> routeSegments) {
        routeSegments.forEach(this::deleteRouteGpxFilesQuietly);
    }

    private TravelPhotoGpsMetadataService.ExtractedPhotoGps extractStoredPhotoGps(TravelMediaStorageService.StoredTravelMedia storedFile) {
        if (storedFile == null || storedFile.contentType() == null || !storedFile.contentType().startsWith("image/")) {
            return null;
        }

        try {
            return travelPhotoGpsMetadataService.extract(
                    travelMediaStorageService.loadAsResource(storedFile.storagePath()),
                    storedFile.contentType()
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private TravelMediaAsset saveMediaAsset(
            AppUser currentUser,
            TravelExpenseRecord record,
            TravelMediaType mediaType,
            String caption,
            TravelMediaStorageService.StoredTravelMedia storedFile,
            TravelPhotoGpsMetadataService.ExtractedPhotoGps extractedPhotoGps
    ) {
        validateStoredMediaType(mediaType, storedFile.contentType());

        TravelMediaAsset asset = new TravelMediaAsset();
        asset.setPlan(record.getPlan());
        asset.setRecord(record);
        asset.setUploadedBy(currentUser);
        asset.setMediaType(mediaType != null ? mediaType : TravelMediaType.PHOTO);
        asset.setOriginalFileName(storedFile.originalFileName());
        asset.setStoredFileName(storedFile.storedFileName());
        asset.setStoragePath(storedFile.storagePath());
        asset.setContentType(storedFile.contentType());
        asset.setFileSize(storedFile.fileSize());
        asset.setCaption(trimToNull(caption));
        if (extractedPhotoGps != null) {
            asset.setGpsLatitude(extractedPhotoGps.latitude());
            asset.setGpsLongitude(extractedPhotoGps.longitude());
            asset.setGpsExtractedAt(java.time.LocalDateTime.now());
        }
        return travelMediaAssetRepository.save(asset);
    }

    private void validateStoredMediaType(TravelMediaType mediaType, String contentType) {
        boolean isImage = contentType != null && contentType.startsWith("image/");
        boolean isPdf = "application/pdf".equals(contentType);

        if ((mediaType == null || mediaType == TravelMediaType.PHOTO) && !isImage) {
            throw new BadRequestException("Photos must use JPG, PNG, WEBP, GIF, or BMP files.");
        }
        if (mediaType == TravelMediaType.RECEIPT && !(isImage || isPdf)) {
            throw new BadRequestException("Receipts must use JPG, PNG, WEBP, GIF, BMP, or PDF files.");
        }
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

    @SafeVarargs
    private final List<String> distinctSorted(java.util.stream.Stream<String>... streams) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (java.util.stream.Stream<String> stream : streams) {
            stream.map(this::trimToNull)
                    .filter(value -> value != null)
                    .sorted(String::compareToIgnoreCase)
                    .forEach(values::add);
        }
        return List.copyOf(values);
    }

    private String normalizeCurrencyCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!normalized.matches("[A-Z]{3}")) {
            throw new BadRequestException("Currency code must be a 3-letter ISO 4217 code.");
        }
        return normalized;
    }

    private String normalizeColorHex(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (normalized.isEmpty()) {
            return DEFAULT_TRAVEL_COLOR;
        }
        if (!normalized.matches("^#[0-9A-F]{6}$")) {
            throw new BadRequestException("Travel color must use #RRGGBB format.");
        }
        return normalized;
    }

    private String resolveRouteLineColor(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (normalized.isEmpty()) {
            return normalizeColorHex(fallback);
        }
        return normalizeColorHex(normalized);
    }

    private void validateCoordinates(BigDecimal latitude, BigDecimal longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new BadRequestException("Latitude and longitude must be provided together.");
        }
    }

    private BigDecimal normalizeCoordinate(BigDecimal value, boolean latitude) {
        if (value == null) {
            return null;
        }

        BigDecimal normalized = value.setScale(7, RoundingMode.HALF_UP);
        BigDecimal min = latitude ? BigDecimal.valueOf(-90) : BigDecimal.valueOf(-180);
        BigDecimal max = latitude ? BigDecimal.valueOf(90) : BigDecimal.valueOf(180);
        if (normalized.compareTo(min) < 0 || normalized.compareTo(max) > 0) {
            throw new BadRequestException(latitude
                    ? "Latitude must be between -90 and 90."
                    : "Longitude must be between -180 and 180.");
        }
        return normalized;
    }

    private void validateRoutePoints(List<TravelRoutePointRequest> points) {
        if (points == null || points.size() < 2) {
            throw new BadRequestException("At least two route points are required.");
        }

        points.forEach(point -> {
            normalizeCoordinate(point.latitude(), true);
            normalizeCoordinate(point.longitude(), false);
        });
    }

    private List<TravelRoutePointRequest> normalizeRoutePointsForStorage(List<TravelRoutePointRequest> points, TravelRouteSourceType sourceType) {
        validateRoutePoints(points);

        List<TravelRoutePointRequest> normalized = points.stream()
                .map(point -> new TravelRoutePointRequest(
                        normalizeCoordinate(point.latitude(), true),
                        normalizeCoordinate(point.longitude(), false),
                        normalizeRoutePointType(point.pointType()),
                        point.linkedMemoryId(),
                        trimRoutePointLabel(point.label())
                ))
                .toList();

        List<TravelRoutePointRequest> deduplicated = deduplicateRoutePoints(normalized);
        if (deduplicated.size() < 2) {
            throw new BadRequestException("At least two distinct route points are required.");
        }

        List<TravelRoutePointRequest> candidate = deduplicated;
        if (sourceType == TravelRouteSourceType.GPX && deduplicated.size() > MAX_STORED_ROUTE_POINTS) {
            candidate = thinRoutePoints(deduplicated, MAX_STORED_ROUTE_POINTS);
        } else if (deduplicated.size() > MAX_STORED_ROUTE_POINTS * 2) {
            candidate = thinRoutePoints(deduplicated, MAX_STORED_ROUTE_POINTS * 2);
        }

        int budget = resolveRoutePathCharBudget();
        String serialized = serializeRoutePoints(candidate, sourceType);
        while (candidate.size() > 2 && serialized.length() > budget) {
            int nextTarget = candidate.size() <= 8
                    ? Math.max(2, candidate.size() - 1)
                    : Math.max(2, (int) Math.ceil(candidate.size() * 0.65));
            candidate = thinRoutePoints(candidate, nextTarget);
            serialized = serializeRoutePoints(candidate, sourceType);
        }

        return candidate;
    }

    private String normalizeRoutePointType(String pointType) {
        String normalized = trimToNull(pointType);
        if (normalized == null) {
            return ROUTE_POINT_TYPE_ROUTE;
        }
        return ROUTE_POINT_TYPE_MEMORY.equalsIgnoreCase(normalized)
                ? ROUTE_POINT_TYPE_MEMORY
                : ROUTE_POINT_TYPE_ROUTE;
    }

    private String trimRoutePointLabel(String label) {
        String trimmed = trimToNull(label);
        if (trimmed == null) {
            return null;
        }
        return trimmed.length() > 160 ? trimmed.substring(0, 160) : trimmed;
    }

    private List<TravelRoutePointRequest> deduplicateRoutePoints(List<TravelRoutePointRequest> points) {
        List<TravelRoutePointRequest> deduplicated = new ArrayList<>();

        for (TravelRoutePointRequest point : points) {
            TravelRoutePointRequest previous = deduplicated.isEmpty() ? null : deduplicated.get(deduplicated.size() - 1);
            if (previous != null
                    && previous.latitude().compareTo(point.latitude()) == 0
                    && previous.longitude().compareTo(point.longitude()) == 0) {
                continue;
            }
            deduplicated.add(point);
        }

        return List.copyOf(deduplicated);
    }

    private List<TravelRoutePointRequest> thinRoutePoints(List<TravelRoutePointRequest> points, int maxPoints) {
        if (points.size() <= maxPoints) {
            return points;
        }

        List<TravelRoutePointRequest> reduced = new ArrayList<>();
        int step = Math.max(1, (int) Math.ceil((double) (points.size() - 1) / Math.max(1, maxPoints - 1)));

        for (int index = 0; index < points.size(); index += step) {
            reduced.add(points.get(index));
        }

        TravelRoutePointRequest lastPoint = points.get(points.size() - 1);
        TravelRoutePointRequest reducedLastPoint = reduced.get(reduced.size() - 1);
        if (reducedLastPoint.latitude().compareTo(lastPoint.latitude()) != 0
                || reducedLastPoint.longitude().compareTo(lastPoint.longitude()) != 0) {
            reduced.add(lastPoint);
        }

        return List.copyOf(reduced);
    }

    private String serializeRoutePoints(List<TravelRoutePointRequest> points, TravelRouteSourceType sourceType) {
        try {
            if (sourceType != TravelRouteSourceType.GPX) {
                return objectMapper.writeValueAsString(points);
            }
            return objectMapper.writeValueAsString(new RoutePathPayload(
                    ROUTE_PATH_FORMAT_POLYLINE6,
                    encodeRoutePolyline(points)
            ));
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("Failed to save route points.");
        }
    }

    private int resolveRoutePathCharBudget() {
        Integer cached = routePathCharBudget;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (routePathCharBudget != null) {
                return routePathCharBudget;
            }

            routePathCharBudget = inspectRoutePathCharBudget();
            return routePathCharBudget;
        }
    }

    private int inspectRoutePathCharBudget() {
        try (var connection = dataSource.getConnection()) {
            var metaData = connection.getMetaData();

            int budget = readRoutePathColumnBudget(metaData, connection.getCatalog(), "travel_route_segments", "route_path_json");
            if (budget > 0) {
                return budget;
            }

            budget = readRoutePathColumnBudget(metaData, connection.getCatalog(), "TRAVEL_ROUTE_SEGMENTS", "ROUTE_PATH_JSON");
            if (budget > 0) {
                return budget;
            }
        } catch (Exception ignored) {
            return DEFAULT_ROUTE_PATH_CHAR_BUDGET;
        }

        return DEFAULT_ROUTE_PATH_CHAR_BUDGET;
    }

    private int readRoutePathColumnBudget(
            java.sql.DatabaseMetaData metaData,
            String catalog,
            String tableName,
            String columnName
    ) throws java.sql.SQLException {
        try (var columns = metaData.getColumns(catalog, null, tableName, columnName)) {
            if (!columns.next()) {
                return -1;
            }

            int columnSize = columns.getInt("COLUMN_SIZE");
            String typeName = columns.getString("TYPE_NAME");
            if (typeName != null) {
                String normalizedType = typeName.trim().toUpperCase();
                if (normalizedType.contains("LONGTEXT") || normalizedType.contains("CLOB")) {
                    return DEFAULT_ROUTE_PATH_CHAR_BUDGET;
                }
                if (normalizedType.contains("TEXT") && columnSize >= DEFAULT_ROUTE_PATH_CHAR_BUDGET) {
                    return DEFAULT_ROUTE_PATH_CHAR_BUDGET;
                }
            }

            if (columnSize > 0) {
                return Math.max(MIN_ROUTE_PATH_CHAR_BUDGET, (int) Math.floor(columnSize * 0.9d));
            }
        }
        return -1;
    }

    private String serializeRouteGpxFiles(List<RouteGpxFile> files) {
        try {
            return objectMapper.writeValueAsString(files == null ? Collections.emptyList() : files);
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("Failed to save GPX file metadata.");
        }
    }

    private List<RouteGpxFile> deserializeRouteGpxFiles(String gpxFilesJson) {
        if (gpxFilesJson == null || gpxFilesJson.isBlank()) {
            return Collections.emptyList();
        }

        try {
            List<RouteGpxFile> files = objectMapper.readValue(gpxFilesJson, new TypeReference<>() {
            });
            return files == null ? Collections.emptyList() : files.stream()
                    .filter(file -> file != null && file.storagePath() != null && !file.storagePath().isBlank())
                    .toList();
        } catch (JsonProcessingException exception) {
            return Collections.emptyList();
        }
    }

    private void deleteRouteGpxFilesQuietly(TravelRouteSegment routeSegment) {
        deserializeRouteGpxFiles(routeSegment.getGpxFilesJson()).forEach(file ->
                travelMediaStorageService.deleteQuietly(file.storagePath())
        );
    }

    private List<TravelRoutePointResponse> resolveRoutePoints(TravelRouteSegment routeSegment) {
        if (routeSegment.getSourceType() == TravelRouteSourceType.GPX) {
            List<RouteGpxFile> routeGpxFiles = deserializeRouteGpxFiles(routeSegment.getGpxFilesJson());
            if (!routeGpxFiles.isEmpty()) {
                try {
                    List<TravelRoutePointRequest> mergedPoints = new ArrayList<>();
                    for (RouteGpxFile routeGpxFile : routeGpxFiles) {
                        Resource resource = travelMediaStorageService.loadAsResource(routeGpxFile.storagePath());
                        mergedPoints.addAll(parseGpxResource(resource));
                    }

                    List<TravelRoutePointRequest> deduplicatedPoints = deduplicateRoutePoints(mergedPoints);
                    if (deduplicatedPoints.size() >= 2) {
                        return deduplicatedPoints.stream()
                                .map(this::toRoutePointResponse)
                                .toList();
                    }
                } catch (RuntimeException ignored) {
                    // Fall back to the reduced path snapshot if the original GPX file is unavailable.
                }
            }
        }

        return deserializeRoutePoints(routeSegment.getRoutePathJson());
    }

    private List<TravelRoutePointRequest> parseGpxResource(Resource resource) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            try (InputStream inputStream = resource.getInputStream()) {
                var documentBuilder = factory.newDocumentBuilder();
                var document = documentBuilder.parse(inputStream);
                List<TravelRoutePointRequest> trackPoints = extractGpxPoints(document, "trkpt");
                if (trackPoints.size() >= 2) {
                    return trackPoints;
                }
                return extractGpxPoints(document, "rtept");
            }
        } catch (Exception exception) {
            throw new BadRequestException("Failed to read GPX route file.");
        }
    }

    private List<TravelRoutePointRequest> extractGpxPoints(org.w3c.dom.Document document, String tagName) {
        NodeList pointNodes = document.getElementsByTagName(tagName);
        List<TravelRoutePointRequest> points = new ArrayList<>();

        for (int index = 0; index < pointNodes.getLength(); index += 1) {
            if (!(pointNodes.item(index) instanceof Element element)) {
                continue;
            }

            String latitudeText = trimToNull(element.getAttribute("lat"));
            String longitudeText = trimToNull(element.getAttribute("lon"));
            if (latitudeText == null || longitudeText == null) {
                continue;
            }

            try {
                points.add(new TravelRoutePointRequest(
                        normalizeCoordinate(new BigDecimal(latitudeText), true),
                        normalizeCoordinate(new BigDecimal(longitudeText), false)
                ));
            } catch (NumberFormatException ignored) {
                // Skip invalid GPX coordinates and keep parsing the remaining track.
            }
        }

        return points;
    }

    private List<TravelRoutePointResponse> deserializeRoutePoints(String routePathJson) {
        if (routePathJson == null || routePathJson.isBlank()) {
            return Collections.emptyList();
        }

        try {
            String trimmed = routePathJson.trim();
            if (trimmed.startsWith("[")) {
                List<TravelRoutePointRequest> points = objectMapper.readValue(trimmed, new TypeReference<>() {
                });
                return points.stream()
                        .map(this::toRoutePointResponse)
                        .toList();
            }

            RoutePathPayload payload = objectMapper.readValue(trimmed, RoutePathPayload.class);
            if (payload == null || payload.encodedPath() == null || payload.encodedPath().isBlank()) {
                return Collections.emptyList();
            }

            if (!ROUTE_PATH_FORMAT_POLYLINE6.equalsIgnoreCase(String.valueOf(payload.format()))) {
                throw new BadRequestException("Failed to read saved route points.");
            }

            return decodeRoutePolyline(payload.encodedPath()).stream()
                    .map(this::toRoutePointResponse)
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("Failed to read saved route points.");
        }
    }

    private TravelRoutePointResponse toRoutePointResponse(TravelRoutePointRequest point) {
        return new TravelRoutePointResponse(
                point.latitude(),
                point.longitude(),
                normalizeRoutePointType(point.pointType()),
                point.linkedMemoryId(),
                trimRoutePointLabel(point.label())
        );
    }

    private String encodeRoutePolyline(List<TravelRoutePointRequest> points) {
        StringBuilder encoded = new StringBuilder();
        long previousLatitude = 0L;
        long previousLongitude = 0L;

        for (TravelRoutePointRequest point : points) {
            long latitude = scaleRouteCoordinate(point.latitude());
            long longitude = scaleRouteCoordinate(point.longitude());
            appendEncodedDifference(encoded, latitude - previousLatitude);
            appendEncodedDifference(encoded, longitude - previousLongitude);
            previousLatitude = latitude;
            previousLongitude = longitude;
        }

        return encoded.toString();
    }

    private List<TravelRoutePointRequest> decodeRoutePolyline(String encodedPath) {
        List<TravelRoutePointRequest> points = new ArrayList<>();
        int index = 0;
        long latitude = 0L;
        long longitude = 0L;

        while (index < encodedPath.length()) {
            DecodeStep latitudeStep = decodePolylineValue(encodedPath, index);
            index = latitudeStep.nextIndex();
            latitude += latitudeStep.delta();

            if (index >= encodedPath.length()) {
                break;
            }

            DecodeStep longitudeStep = decodePolylineValue(encodedPath, index);
            index = longitudeStep.nextIndex();
            longitude += longitudeStep.delta();

            points.add(new TravelRoutePointRequest(
                    BigDecimal.valueOf(latitude, ROUTE_PATH_PRECISION).setScale(7, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(longitude, ROUTE_PATH_PRECISION).setScale(7, RoundingMode.HALF_UP)
            ));
        }

        return List.copyOf(points);
    }

    private long scaleRouteCoordinate(BigDecimal coordinate) {
        return coordinate
                .movePointRight(ROUTE_PATH_PRECISION)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    private void appendEncodedDifference(StringBuilder builder, long difference) {
        long value = difference < 0 ? ~(difference << 1) : (difference << 1);
        while (value >= 0x20) {
            builder.append((char) ((0x20 | (value & 0x1f)) + 63));
            value >>= 5;
        }
        builder.append((char) (value + 63));
    }

    private DecodeStep decodePolylineValue(String encodedPath, int startIndex) {
        long result = 0L;
        int shift = 0;
        int index = startIndex;
        int chunk;

        do {
            chunk = encodedPath.charAt(index++) - 63;
            result |= (long) (chunk & 0x1f) << shift;
            shift += 5;
        } while (chunk >= 0x20 && index < encodedPath.length());

        long delta = (result & 1L) != 0L ? ~(result >> 1) : (result >> 1);
        return new DecodeStep(delta, index);
    }

    private record DecodeStep(
            long delta,
            int nextIndex
    ) {
    }

    private TravelMediaType resolveMediaType(TravelRecordType recordType, TravelMediaType mediaType) {
        if (recordType == TravelRecordType.MEMORY) {
            return TravelMediaType.PHOTO;
        }
        return mediaType != null ? mediaType : TravelMediaType.PHOTO;
    }

    private TravelPlanStatus resolvePlanStatus(String value) {
        if (value == null || value.isBlank()) {
            return TravelPlanStatus.PLANNED;
        }

        try {
            return TravelPlanStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Unsupported travel status.");
        }
    }

    private TravelPlanStatus resolvePlanStatus(TravelPlanStatus value) {
        return value != null ? value : TravelPlanStatus.PLANNED;
    }

    private boolean isLedgerRecord(TravelExpenseRecord record) {
        return record.getRecordType() == null || record.getRecordType() == TravelRecordType.LEDGER;
    }

    private boolean isMemoryRecord(TravelExpenseRecord record) {
        return record.getRecordType() == TravelRecordType.MEMORY;
    }

    private boolean hasCoordinates(TravelExpenseRecord record) {
        return record.getLatitude() != null && record.getLongitude() != null;
    }

    private double calculateDistanceMeters(TravelExpenseRecord origin, TravelExpenseRecord target) {
        double originLatitude = origin.getLatitude().doubleValue();
        double originLongitude = origin.getLongitude().doubleValue();
        double targetLatitude = target.getLatitude().doubleValue();
        double targetLongitude = target.getLongitude().doubleValue();

        double latitudeDistance = Math.toRadians(targetLatitude - originLatitude);
        double longitudeDistance = Math.toRadians(targetLongitude - originLongitude);
        double startLatitude = Math.toRadians(originLatitude);
        double endLatitude = Math.toRadians(targetLatitude);

        double haversine = Math.pow(Math.sin(latitudeDistance / 2), 2)
                + Math.cos(startLatitude) * Math.cos(endLatitude) * Math.pow(Math.sin(longitudeDistance / 2), 2);
        double angularDistance = 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
        return 6371000d * angularDistance;
    }

    private String resolveMissingRecordMessage(TravelRecordType recordType) {
        return recordType == TravelRecordType.MEMORY ? "Travel memory not found." : "Travel ledger record not found.";
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record MediaDownload(String storagePath, String contentType, String fileName) {
    }
}
