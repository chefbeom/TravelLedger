package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.out.TravelRoutePayloadPort;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData;
import com.playdata.calen.travel.domain.policy.*;
import com.playdata.calen.travel.dto.*;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.TravelPublicMediaTokenService;
import java.math.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import static com.playdata.calen.travel.adapter.support.TravelPlanDetailMapper.*;

/** Frozen pre-extraction atlas/feed implementation at HEAD 4aeb6d7d.
 * Only previously verified cluster, page, route and media projection helpers are reused. */
@RequiredArgsConstructor
public class TravelAtlasLegacyOracle {
    private final AppUserService appUserService;
    private final TravelPlanRepository travelPlanRepository;
    private final TravelExpenseRecordRepository travelExpenseRecordRepository;
    private final TravelMediaAssetRepository travelMediaAssetRepository;
    private final TravelRouteSegmentRepository travelRouteSegmentRepository;
    private final TravelPlanShareRepository travelPlanShareRepository;
    private final TravelPublicMediaTokenService travelPublicMediaTokenService;
    private final TravelPhotoClusterPolicy travelPhotoClusterPolicy;
    private final TravelRoutePayloadPort routePayload;
    private static final BigDecimal ZERO_DISTANCE = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
    private static final int DEFAULT_LIST_PAGE_SIZE = 10, MAX_LIST_PAGE_SIZE = 20;
    private static final String SHARE_VISIBILITY_PUBLIC = "PUBLIC", SHARE_VISIBILITY_RESTRICTED = "RESTRICTED";
    private record PublicAtlasPlanAccess(TravelPlan plan, TravelPlanShare share) {}

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

    public TravelPublicTripsOverviewResponse getPublicTripsOverview(Long userId) {
        appUserService.getRequiredUser(userId);

        List<PublicAtlasPlanAccess> planAccesses = getPublicAtlasPlanAccesses(userId);
        List<Long> planIds = planAccesses.stream()
                .map(access -> access.plan().getId())
                .toList();
        if (planIds.isEmpty()) {
            return new TravelPublicTripsOverviewResponse(
                    0,
                    0,
                    0,
                    0,
                    ZERO_DISTANCE,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList()
            );
        }

        Map<Long, TravelPlanShare> viewerShareByPlanId = buildViewerShareByPlanId(planAccesses);
        Function<TravelMediaAsset, String> mediaUrlResolver = asset -> buildPublicAtlasMediaUrl(asset, viewerShareByPlanId);
        List<TravelPlan> visiblePlans = planAccesses.stream().map(PublicAtlasPlanAccess::plan).toList();
        List<TravelExpenseRecord> publicMemoryRecords = travelExpenseRecordRepository
                .findAllByPlanIdInAndRecordType(planIds, TravelRecordType.MEMORY)
                .stream()
                .sorted(RECORD_ORDER)
                .toList();
        List<TravelMediaAsset> publicPhotoMediaItems = getPublicTripPhotoMediaItems(planIds);
        List<TravelRouteSegment> publicRouteSegments = travelRouteSegmentRepository
                .findAllByPlanIdInOrderByRouteDateDescIdDesc(planIds)
                .stream()
                .sorted(ROUTE_ORDER)
                .toList();
        List<TravelPhotoClusterData.PhotoCluster> photoClusters = buildMyMapPhotoClusters(publicPhotoMediaItems);
        Map<Long, TravelMediaAsset> mediaAssetById = publicPhotoMediaItems.stream()
                .collect(Collectors.toMap(TravelMediaAsset::getId, Function.identity()));
        Map<Long, List<TravelExpenseRecord>> memoryRecordsByPlan = publicMemoryRecords.stream()
                .collect(Collectors.groupingBy(record -> record.getPlan().getId()));
        Map<Long, List<TravelMediaAsset>> mediaItemsByPlan = publicPhotoMediaItems.stream()
                .collect(Collectors.groupingBy(asset -> asset.getPlan().getId()));
        Map<Long, List<TravelRouteSegment>> routesByPlan = publicRouteSegments.stream()
                .collect(Collectors.groupingBy(route -> route.getPlan().getId()));

        TravelPublicTripsOverviewResponse response = new TravelPublicTripsOverviewResponse(
                visiblePlans.size(),
                publicPhotoMediaItems.size(),
                photoClusters.size(),
                publicRouteSegments.size(),
                sumDistanceKm(publicRouteSegments),
                planAccesses.stream()
                        .map(plan -> toPublicTripSummaryResponse(
                                plan.plan(),
                                memoryRecordsByPlan.getOrDefault(plan.plan().getId(), Collections.emptyList()),
                                mediaItemsByPlan.getOrDefault(plan.plan().getId(), Collections.emptyList()),
                                routesByPlan.getOrDefault(plan.plan().getId(), Collections.emptyList()),
                                mediaUrlResolver,
                                plan.share()
                        ))
                        .toList(),
                photoClusters.stream()
                        .map(cluster -> toPublicPhotoClusterSummaryResponse(cluster, mediaAssetById, mediaUrlResolver, viewerShareByPlanId))
                        .toList(),
                photoClusters.stream()
                        .flatMap(cluster -> toPublicPhotoPins(cluster, mediaAssetById, mediaUrlResolver, viewerShareByPlanId).stream())
                        .toList(),
                publicRouteSegments.stream().map(this::toRouteSegmentResponse).toList()
        );
        return response;
    }

    public TravelMyMapPhotoClusterPageResponse getPublicTripPhotoClusterDetail(
            Long userId,
            Long clusterId,
            Integer page,
            Integer size,
            Long focusMediaId
    ) {
        appUserService.getRequiredUser(userId);
        TravelMyMapPhotoClusterDetailResponse cluster = resolvePublicTripPhotoClusterDetail(userId, clusterId);
        return toMyMapPhotoClusterPageResponse(cluster, page, size, focusMediaId);
    }

    private List<PublicAtlasPlanAccess> getPublicAtlasPlanAccesses(Long userId) {
        Map<Long, PublicAtlasPlanAccess> accessByPlanId = new LinkedHashMap<>();

        travelPlanRepository.findAllByPublicSharedTrueAndStatusOrderByPublicSharedAtDescStartDateDescIdDesc(TravelPlanStatus.COMPLETED)
                .forEach(plan -> accessByPlanId.put(plan.getId(), new PublicAtlasPlanAccess(plan, null)));
        travelPlanShareRepository.findAllByRecipientIdAndPlan_StatusOrderByCreatedAtDescIdDesc(userId, TravelPlanStatus.COMPLETED)
                .forEach(share -> accessByPlanId.putIfAbsent(
                        share.getPlan().getId(),
                        new PublicAtlasPlanAccess(share.getPlan(), share)
                ));

        return List.copyOf(accessByPlanId.values());
    }

    private Map<Long, TravelPlanShare> buildViewerShareByPlanId(List<PublicAtlasPlanAccess> accesses) {
        return accesses.stream()
                .filter(access -> access.share() != null)
                .collect(Collectors.toMap(
                        access -> access.plan().getId(),
                        PublicAtlasPlanAccess::share,
                        (left, right) -> left
                ));
    }

    private List<TravelMediaAsset> getPublicTripPhotoMediaItems(Collection<Long> planIds) {
        if (planIds == null || planIds.isEmpty()) {
            return List.of();
        }

        return travelMediaAssetRepository.findAllByPlanIdInAndMediaTypeOrderByUploadedAtDescIdDesc(planIds, TravelMediaType.PHOTO).stream()
                .filter(asset -> isMemoryRecord(asset.getRecord()))
                .filter(this::hasClusterCoordinates)
                .toList();
    }

    private List<TravelPhotoClusterData.PhotoCluster> buildMyMapPhotoClusters(List<TravelMediaAsset> photoMediaItems) {
        List<TravelPhotoClusterData.PhotoPoint> points = photoMediaItems.stream()
                .map(this::toPhotoClusterPoint)
                .toList();
        return travelPhotoClusterPolicy.cluster(points);
    }

    private TravelMyMapPhotoClusterDetailResponse resolvePublicTripPhotoClusterDetail(Long userId, Long clusterId) {
        List<PublicAtlasPlanAccess> planAccesses = getPublicAtlasPlanAccesses(userId);
        Map<Long, TravelPlanShare> viewerShareByPlanId = buildViewerShareByPlanId(planAccesses);
        Function<TravelMediaAsset, String> mediaUrlResolver = asset -> buildPublicAtlasMediaUrl(asset, viewerShareByPlanId);
        List<Long> planIds = planAccesses.stream()
                .map(access -> access.plan().getId())
                .toList();
        Map<Long, TravelMediaAsset> mediaAssetById = getPublicTripPhotoMediaItems(planIds).stream()
                .collect(Collectors.toMap(TravelMediaAsset::getId, Function.identity()));
        return buildMyMapPhotoClusters(new ArrayList<>(mediaAssetById.values())).stream()
                .filter(cluster -> cluster.id() != null && cluster.id().equals(clusterId))
                .findFirst()
                .map(cluster -> toPublicPhotoClusterDetailResponse(cluster, mediaAssetById, mediaUrlResolver))
                .orElseThrow(() -> new NotFoundException("Public travel photo cluster not found."));
    }

    private TravelPhotoClusterData.PhotoPoint toPhotoClusterPoint(TravelMediaAsset mediaAsset) {
        return TravelClusterPhotoMapper.point(mediaAsset);
    }

    private boolean hasClusterCoordinates(TravelMediaAsset mediaAsset) {
        return TravelClusterPhotoMapper.hasCoordinates(mediaAsset);
    }

    private TravelPublicTripSummaryResponse toPublicTripSummaryResponse(
            TravelPlan plan,
            List<TravelExpenseRecord> memoryRecords,
            List<TravelMediaAsset> mediaItems,
            List<TravelRouteSegment> routeSegments,
            Function<TravelMediaAsset, String> mediaUrlResolver,
            TravelPlanShare viewerShare
    ) {
        String representativePhotoUrl = mediaItems.stream()
                .sorted(MEDIA_ORDER)
                .findFirst()
                .map(mediaUrlResolver)
                .orElse("");

        return new TravelPublicTripSummaryResponse(
                plan.getId(),
                plan.getName(),
                plan.getDestination(),
                plan.getStartDate(),
                plan.getEndDate(),
                resolvePlanStatus(plan.getStatus()).name(),
                normalizeColorHex(plan.getColorHex()),
                plan.getOwner().getLoginId(),
                plan.getOwner().getDisplayName(),
                resolvePublicSharedAt(plan, viewerShare),
                memoryRecords.size(),
                mediaItems.size(),
                routeSegments.size(),
                sumDistanceKm(routeSegments),
                representativePhotoUrl,
                resolveShareVisibility(plan, viewerShare),
                viewerShare != null ? viewerShare.getId() : null
        );
    }

    private TravelPublicPhotoClusterSummaryResponse toPublicPhotoClusterSummaryResponse(
            TravelPhotoClusterData.PhotoCluster cluster,
            Map<Long, TravelMediaAsset> mediaAssetById,
            Function<TravelMediaAsset, String> mediaUrlResolver,
            Map<Long, TravelPlanShare> viewerShareByPlanId
    ) {
        TravelPhotoClusterData.PhotoPoint representative = cluster.representative();
        TravelMediaAsset representativeAsset = mediaAssetById.get(representative.mediaId());
        TravelPlan plan = representativeAsset != null ? representativeAsset.getPlan() : null;
        AppUser owner = plan != null ? plan.getOwner() : null;
        TravelPlanShare viewerShare = plan != null ? viewerShareByPlanId.get(plan.getId()) : null;

        return new TravelPublicPhotoClusterSummaryResponse(
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
                representativeAsset != null ? mediaUrlResolver.apply(representativeAsset) : "",
                owner != null ? owner.getLoginId() : "",
                owner != null ? owner.getDisplayName() : "",
                resolvePublicSharedAt(plan, viewerShare),
                resolveShareVisibility(plan, viewerShare),
                viewerShare != null ? viewerShare.getId() : null
        );
    }

    private List<TravelPublicPhotoPinResponse> toPublicPhotoPins(
            TravelPhotoClusterData.PhotoCluster cluster,
            Map<Long, TravelMediaAsset> mediaAssetById,
            Function<TravelMediaAsset, String> mediaUrlResolver,
            Map<Long, TravelPlanShare> viewerShareByPlanId
    ) {
        return (cluster.members() == null ? List.<TravelPhotoClusterData.PhotoPoint>of() : cluster.members()).stream()
                .map(member -> toPublicPhotoPinResponse(
                        member,
                        cluster,
                        mediaAssetById.get(member.mediaId()),
                        mediaUrlResolver,
                        viewerShareByPlanId
                ))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private TravelPublicPhotoPinResponse toPublicPhotoPinResponse(
            TravelPhotoClusterData.PhotoPoint member,
            TravelPhotoClusterData.PhotoCluster cluster,
            TravelMediaAsset mediaAsset,
            Function<TravelMediaAsset, String> mediaUrlResolver,
            Map<Long, TravelPlanShare> viewerShareByPlanId
    ) {
        if (member == null || mediaAsset == null) {
            return null;
        }

        TravelPlan plan = mediaAsset.getPlan();
        AppUser owner = plan.getOwner();
        TravelPlanShare viewerShare = viewerShareByPlanId.get(plan.getId());
        return new TravelPublicPhotoPinResponse(
                member.mediaId(),
                cluster.id(),
                member.recordId(),
                member.planId(),
                member.planName(),
                normalizeColorHex(member.planColorHex()),
                member.memoryDate(),
                member.memoryTime(),
                member.category(),
                member.title(),
                member.country(),
                member.region(),
                member.placeName(),
                member.latitude(),
                member.longitude(),
                mediaUrlResolver.apply(mediaAsset),
                member.mediaId().equals(cluster.representative().mediaId()),
                member.representativeOverride(),
                owner.getLoginId(),
                owner.getDisplayName(),
                resolvePublicSharedAt(plan, viewerShare),
                resolveShareVisibility(plan, viewerShare),
                viewerShare != null ? viewerShare.getId() : null
        );
    }

    private TravelMyMapPhotoClusterDetailResponse toPublicPhotoClusterDetailResponse(
            TravelPhotoClusterData.PhotoCluster cluster,
            Map<Long, TravelMediaAsset> mediaAssetById,
            Function<TravelMediaAsset, String> mediaUrlResolver
    ) {
        TravelMediaAsset representativeAsset = mediaAssetById.get(cluster.representative().mediaId());
        TravelMediaResponse representativePhoto = representativeAsset != null
                ? toMediaResponse(representativeAsset, mediaUrlResolver.apply(representativeAsset))
                : null;
        List<TravelMediaResponse> photos = cluster.members().stream()
                .map(member -> mediaAssetById.get(member.mediaId()))
                .filter(java.util.Objects::nonNull)
                .map(asset -> toMediaResponse(asset, mediaUrlResolver.apply(asset)))
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

    private TravelMyMapPhotoClusterPageResponse toMyMapPhotoClusterPageResponse(
            TravelMyMapPhotoClusterDetailResponse cluster, Integer page, Integer size, Long focusMediaId
    ) {
        var slice = TravelClusterPagePolicy.slice(cluster.photos(), cluster.representativeMediaId(), focusMediaId,
                page, size, TravelMediaResponse::id, TravelMediaResponse::expenseDate, TravelMediaResponse::expenseTime, TravelMediaResponse::uploadedAt);
        return new TravelMyMapPhotoClusterPageResponse(cluster.id(), cluster.representativeMediaId(), cluster.representativeRecordId(),
                cluster.latitude(), cluster.longitude(), cluster.photoCount(), cluster.memoryCount(), cluster.maxDistanceMeters(),
                cluster.representativeOverride(), cluster.representativePhoto(), slice.photos(), slice.page(), slice.size(), slice.total(), slice.hasNext());
    }

    private TravelRouteSegmentResponse toRouteSegmentResponse(TravelRouteSegment routeSegment) {
        return TravelRouteMapper.response(TravelRouteMapper.result(routeSegment,routePayload,false));
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

    private String buildPublicAtlasMediaUrl(TravelMediaAsset mediaAsset, Map<Long, TravelPlanShare> viewerShareByPlanId) {
        TravelPlan plan = mediaAsset.getPlan();
        if (Boolean.TRUE.equals(plan.getPublicShared())) {
            return buildPublicMediaUrl(mediaAsset.getId());
        }

        TravelPlanShare viewerShare = viewerShareByPlanId.get(plan.getId());
        if (viewerShare == null) {
            throw new NotFoundException("Shared media not found.");
        }
        return "/api/travel/shared-exhibits/" + viewerShare.getId() + "/media/" + mediaAsset.getId() + "/content";
    }

    private LocalDateTime resolvePublicSharedAt(TravelPlan plan, TravelPlanShare viewerShare) {
        if (plan == null) {
            return null;
        }
        if (Boolean.TRUE.equals(plan.getPublicShared())) {
            return plan.getPublicSharedAt();
        }
        return viewerShare != null ? viewerShare.getCreatedAt() : null;
    }

    private String resolveShareVisibility(TravelPlan plan, TravelPlanShare viewerShare) {
        return plan != null && Boolean.TRUE.equals(plan.getPublicShared())
                ? SHARE_VISIBILITY_PUBLIC
                : (viewerShare != null ? SHARE_VISIBILITY_RESTRICTED : SHARE_VISIBILITY_PUBLIC);
    }

    private String normalizeColorHex(String value) {
        return TravelPlanPolicy.color(value);
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

    private BigDecimal sumDistanceKm(Collection<TravelRouteSegment> routes) {
        return routes.stream()
                .map(TravelRouteSegment::getDistanceKm)
                .filter(value -> value != null)
                .reduce(ZERO_DISTANCE, BigDecimal::add)
                .setScale(3, RoundingMode.HALF_UP);
    }

    private TravelPlanStatus resolvePlanStatus(TravelPlanStatus value) {
        return TravelPlanPolicy.status(value);
    }

    private boolean isMemoryRecord(TravelExpenseRecord record) {
        return TravelRecordPolicy.matches(record.getRecordType(), TravelRecordType.MEMORY);
    }

    private TravelMediaResponse toMediaResponse(TravelMediaAsset mediaAsset, String contentUrl) {
        return TravelClusterDetailMapper.response(TravelClusterDetailMapper.media(mediaAsset), contentUrl);
    }
}
