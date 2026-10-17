package com.playdata.calen.travel.adapter.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.*;
import com.playdata.calen.travel.application.port.out.TravelRoutePayloadPort;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData;
import com.playdata.calen.travel.domain.policy.*;
import com.playdata.calen.travel.dto.*;
import com.playdata.calen.travel.repository.*;
import java.math.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import static com.playdata.calen.travel.adapter.support.TravelPlanDetailMapper.*;

/** Frozen map-link workflow from the pre-extraction worktree at HEAD 4aeb6d7d.
 * Reuses only previously verified cluster/page, route payload and media DTO mappings. */
@RequiredArgsConstructor
public class TravelMapLinkLegacyOracle {
    private final TravelMapShareLinkRepository travelMapShareLinkRepository;
    private final TravelPlanRepository travelPlanRepository;
    private final TravelExpenseRecordRepository travelExpenseRecordRepository;
    private final TravelMediaAssetRepository travelMediaAssetRepository;
    private final TravelRouteSegmentRepository travelRouteSegmentRepository;
    private final ObjectMapper objectMapper;
    private final TravelPhotoClusterPolicy travelPhotoClusterPolicy;
    private final TravelRoutePayloadPort routePayload;
    private static final BigDecimal ZERO_DISTANCE = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
    private static final int MAX_LOGIN_PREVIEW_MARKERS = 80, MAX_LOGIN_PREVIEW_ROUTES = 12, MAX_LOGIN_PREVIEW_POINTS_PER_ROUTE = 160;
    private record TravelMapShareScope(List<Long> planIds, Set<Long> excludedRecordIds, Set<Long> excludedMediaIds, Set<Long> excludedRouteIds) {}
    private record TravelMapSharePhotoData(List<TravelPhotoClusterData.PhotoCluster> clusters, Map<Long, TravelMediaAsset> mediaAssetById) {}

    public TravelPublicMapShareResponse getTravelMapShare(String token) {
        TravelMapShareLink shareLink = getRequiredActiveTravelMapShareLink(token);
        TravelMapShareScope scope = readTravelMapShareScope(shareLink);
        return new TravelPublicMapShareResponse(
                shareLink.getToken(),
                shareLink.getTitle(),
                shareLink.getOwner().getLoginId(),
                shareLink.getOwner().getDisplayName(),
                scope.planIds(),
                shareLink.getCreatedAt(),
                buildTravelMapShareOverview(shareLink, scope)
        );
    }

    public TravelLoginMapPreviewResponse getTravelMapShareLoginPreview(String token) {
        TravelMapShareLink shareLink = getRequiredActiveTravelMapShareLink(token);
        TravelMapShareScope scope = readTravelMapShareScope(shareLink);
        List<Long> planIds = loadVisibleTravelMapSharePlanIds(shareLink, scope);
        if (planIds.isEmpty()) {
            return emptyTravelLoginMapPreview();
        }

        List<TravelExpenseRecord> visibleMarkers = loadTravelMapShareLoginPreviewMarkers(planIds, scope);
        Set<Long> visiblePlanIds = new HashSet<>(planIds);
        Map<Long, Long> photoIdByRecordId = visibleMarkers.isEmpty()
                ? Map.of()
                : travelMediaAssetRepository.findAllByRecordIdInOrderByUploadedAtDescIdDesc(
                                visibleMarkers.stream().map(TravelExpenseRecord::getId).toList()
                        ).stream()
                        .filter(asset -> isVisibleTravelMapShareMedia(asset, visiblePlanIds, scope))
                        .collect(Collectors.toMap(
                                asset -> asset.getRecord().getId(),
                                TravelMediaAsset::getId,
                                (existing, ignored) -> existing
                        ));

        List<TravelLoginMapPreviewMarkerResponse> numberedMarkers = new ArrayList<>(visibleMarkers.size());
        for (int index = 0; index < visibleMarkers.size(); index += 1) {
            TravelExpenseRecord record = visibleMarkers.get(index);
            int markerNumber = index + 1;
            Long photoId = photoIdByRecordId.get(record.getId());
            numberedMarkers.add(new TravelLoginMapPreviewMarkerResponse(
                    markerNumber,
                    roundLoginPreviewCoordinate(record.getLatitude()),
                    roundLoginPreviewCoordinate(record.getLongitude()),
                    photoId == null ? null : "/api/travel/public/login-map-preview/markers/" + markerNumber + "/thumbnail"
            ));
        }

        List<TravelRouteSegment> visibleRoutes = travelRouteSegmentRepository
                .findAllByPlanIdInOrderByRouteDateDescIdDesc(planIds).stream()
                .filter(route -> !scope.excludedRouteIds().contains(route.getId()))
                .sorted(ROUTE_ORDER)
                .limit(MAX_LOGIN_PREVIEW_ROUTES)
                .toList();
        List<TravelLoginMapPreviewRouteResponse> routes = new ArrayList<>();
        for (TravelRouteSegment route : visibleRoutes) {
            List<TravelLoginMapPreviewPointResponse> points = sampleLoginPreviewRoute(resolveRoutePoints(route));
            if (points.size() >= 2) {
                routes.add(new TravelLoginMapPreviewRouteResponse(routes.size() + 1, points));
            }
        }

        return new TravelLoginMapPreviewResponse(
                true,
                "여행 기록 미리보기",
                numberedMarkers.size(),
                routes.size(),
                numberedMarkers,
                routes
        );
    }

    public MediaDownload getTravelMapShareLoginPreviewMarkerMediaDownload(String token, int markerNumber) {
        if (markerNumber < 1 || markerNumber > MAX_LOGIN_PREVIEW_MARKERS) {
            throw new NotFoundException("Login map preview thumbnail not found.");
        }

        TravelMapShareLink shareLink = getRequiredActiveTravelMapShareLink(token);
        TravelMapShareScope scope = readTravelMapShareScope(shareLink);
        List<Long> planIds = loadVisibleTravelMapSharePlanIds(shareLink, scope);
        if (planIds.isEmpty()) {
            throw new NotFoundException("Login map preview thumbnail not found.");
        }
        List<TravelExpenseRecord> visibleMarkers = loadTravelMapShareLoginPreviewMarkers(planIds, scope);
        if (visibleMarkers.size() < markerNumber) {
            throw new NotFoundException("Login map preview thumbnail not found.");
        }

        TravelExpenseRecord marker = visibleMarkers.get(markerNumber - 1);
        Set<Long> visiblePlanIds = new HashSet<>(planIds);
        TravelMediaAsset photo = travelMediaAssetRepository
                .findAllByRecordIdInOrderByUploadedAtDescIdDesc(List.of(marker.getId())).stream()
                .filter(asset -> isVisibleTravelMapShareMedia(asset, visiblePlanIds, scope))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Login map preview thumbnail not found."));
        return new MediaDownload(photo.getStoragePath(), photo.getContentType(), photo.getOriginalFileName());
    }

    private List<TravelExpenseRecord> loadTravelMapShareLoginPreviewMarkers(
            List<Long> planIds,
            TravelMapShareScope scope
    ) {
        if (planIds == null || planIds.isEmpty()) {
            return List.of();
        }
        return travelExpenseRecordRepository.findAllByPlanIdInAndRecordType(planIds, TravelRecordType.MEMORY).stream()
                .filter(this::hasCoordinates)
                .filter(record -> !scope.excludedRecordIds().contains(record.getId()))
                .sorted(RECORD_ORDER)
                .limit(MAX_LOGIN_PREVIEW_MARKERS)
                .toList();
    }

    private List<TravelLoginMapPreviewPointResponse> sampleLoginPreviewRoute(List<TravelRoutePointResponse> source) {
        List<TravelRoutePointResponse> valid = source.stream()
                .filter(point -> point != null && point.latitude() != null && point.longitude() != null)
                .toList();
        if (valid.size() < 2) {
            return List.of();
        }
        int stride = Math.max(1, (int) Math.ceil((double) valid.size() / MAX_LOGIN_PREVIEW_POINTS_PER_ROUTE));
        List<TravelLoginMapPreviewPointResponse> result = new ArrayList<>();
        for (int index = 0; index < valid.size(); index += stride) {
            TravelRoutePointResponse point = valid.get(index);
            result.add(new TravelLoginMapPreviewPointResponse(
                    roundLoginPreviewCoordinate(point.latitude()),
                    roundLoginPreviewCoordinate(point.longitude())
            ));
        }
        TravelRoutePointResponse last = valid.get(valid.size() - 1);
        TravelLoginMapPreviewPointResponse roundedLast = new TravelLoginMapPreviewPointResponse(
                roundLoginPreviewCoordinate(last.latitude()),
                roundLoginPreviewCoordinate(last.longitude())
        );
        if (!result.get(result.size() - 1).equals(roundedLast)) {
            result.add(roundedLast);
        }
        return result;
    }

    private BigDecimal roundLoginPreviewCoordinate(BigDecimal coordinate) {
        return coordinate.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private TravelLoginMapPreviewResponse emptyTravelLoginMapPreview() {
        return new TravelLoginMapPreviewResponse(false, "여행 기록 미리보기", 0, 0, List.of(), List.of());
    }

    public TravelMyMapPhotoClusterPageResponse getTravelMapSharePhotoCluster(
            String token,
            Long clusterId,
            Integer page,
            Integer size,
            Long focusMediaId
    ) {
        TravelMapShareLink shareLink = getRequiredActiveTravelMapShareLink(token);
        TravelMapShareScope scope = readTravelMapShareScope(shareLink);
        List<Long> planIds = loadVisibleTravelMapSharePlanIds(shareLink, scope);
        TravelMapSharePhotoData photoData = buildTravelMapSharePhotoData(shareLink, scope, planIds);
        TravelMyMapPhotoClusterDetailResponse cluster = photoData.clusters().stream()
                .filter(candidate -> candidate.id() != null && candidate.id().equals(clusterId))
                .findFirst()
                .map(candidate -> toTravelMapSharePhotoClusterDetailResponse(candidate, photoData.mediaAssetById(), shareLink.getToken()))
                .orElseThrow(() -> new NotFoundException("Shared travel photo cluster not found."));
        return toMyMapPhotoClusterPageResponse(cluster, page, size, focusMediaId);
    }

    public MediaDownload getTravelMapShareMediaDownload(String token, Long mediaId) {
        TravelMapShareLink shareLink = getRequiredActiveTravelMapShareLink(token);
        TravelMapShareScope scope = readTravelMapShareScope(shareLink);
        Set<Long> planIds = new HashSet<>(loadVisibleTravelMapSharePlanIds(shareLink, scope));
        TravelMediaAsset mediaAsset = travelMediaAssetRepository.findById(mediaId)
                .orElseThrow(() -> new NotFoundException("Shared travel media not found."));
        if (!isVisibleTravelMapShareMedia(mediaAsset, planIds, scope)) {
            throw new NotFoundException("Shared travel media not found.");
        }
        return new MediaDownload(mediaAsset.getStoragePath(), mediaAsset.getContentType(), mediaAsset.getOriginalFileName());
    }

    private TravelMapShareLink getRequiredActiveTravelMapShareLink(String token) {
        String normalizedToken = trimToNull(token);
        if (normalizedToken == null) {
            throw new NotFoundException("Shared travel map not found.");
        }
        return travelMapShareLinkRepository.findByTokenAndActiveTrue(normalizedToken)
                .orElseThrow(() -> new NotFoundException("Shared travel map not found."));
    }

    private TravelMapShareScope readTravelMapShareScope(TravelMapShareLink shareLink) {
        return new TravelMapShareScope(
                deserializeIdList(shareLink.getPlanIdsJson()),
                toIdSet(deserializeIdList(shareLink.getExcludedRecordIdsJson())),
                toIdSet(deserializeIdList(shareLink.getExcludedMediaIdsJson())),
                toIdSet(deserializeIdList(shareLink.getExcludedRouteIdsJson()))
        );
    }

    private TravelMyMapOverviewResponse buildTravelMapShareOverview(TravelMapShareLink shareLink, TravelMapShareScope scope) {
        List<Long> planIds = loadVisibleTravelMapSharePlanIds(shareLink, scope);
        if (planIds.isEmpty()) {
            return new TravelMyMapOverviewResponse(0, 0, 0, 0, 0, ZERO_DISTANCE, List.of(), List.of(), List.of(), List.of());
        }

        List<TravelExpenseRecord> markers = travelExpenseRecordRepository.findAllByPlanIdInAndRecordType(planIds, TravelRecordType.MEMORY).stream()
                .filter(this::hasCoordinates)
                .filter(record -> !scope.excludedRecordIds().contains(record.getId()))
                .sorted(RECORD_ORDER)
                .toList();
        List<Long> markerRecordIds = markers.stream().map(TravelExpenseRecord::getId).toList();
        Map<Long, String> markerPhotoUrls = markerRecordIds.isEmpty()
                ? Map.of()
                : travelMediaAssetRepository.findAllByRecordIdInOrderByUploadedAtDescIdDesc(markerRecordIds).stream()
                        .filter(asset -> asset.getMediaType() == TravelMediaType.PHOTO)
                        .filter(asset -> !scope.excludedMediaIds().contains(asset.getId()))
                        .collect(Collectors.toMap(
                                asset -> asset.getRecord().getId(),
                                asset -> buildTravelMapShareMediaUrl(shareLink.getToken(), asset.getId()),
                                (existing, ignored) -> existing
                        ));

        List<TravelRouteSegment> routeSegments = travelRouteSegmentRepository.findAllByPlanIdInOrderByRouteDateDescIdDesc(planIds).stream()
                .filter(route -> !scope.excludedRouteIds().contains(route.getId()))
                .sorted(ROUTE_ORDER)
                .toList();
        TravelMapSharePhotoData photoData = buildTravelMapSharePhotoData(shareLink, scope, planIds);

        return new TravelMyMapOverviewResponse(
                planIds.size(),
                markers.size(),
                photoData.mediaAssetById().size(),
                photoData.clusters().size(),
                routeSegments.size(),
                sumDistanceKm(routeSegments),
                markers.stream().map(record -> toMyMapMarkerSummaryResponse(record, markerPhotoUrls.get(record.getId()))).toList(),
                photoData.clusters().stream()
                        .map(cluster -> toTravelMapSharePhotoClusterSummaryResponse(cluster, shareLink.getToken()))
                        .toList(),
                photoData.clusters().stream()
                        .flatMap(cluster -> toTravelMapSharePhotoPins(cluster, photoData.mediaAssetById(), shareLink.getToken()).stream())
                        .toList(),
                routeSegments.stream().map(this::toMyMapRouteResponse).toList()
        );
    }

    private TravelMapSharePhotoData buildTravelMapSharePhotoData(
            TravelMapShareLink shareLink,
            TravelMapShareScope scope,
            Collection<Long> planIds
    ) {
        Set<Long> visiblePlanIds = new HashSet<>(planIds == null ? List.of() : planIds);
        List<TravelMediaAsset> photoMediaItems = travelMediaAssetRepository.findAllByPlanIdInAndMediaTypeOrderByUploadedAtDescIdDesc(visiblePlanIds, TravelMediaType.PHOTO).stream()
                .filter(asset -> isVisibleTravelMapShareMedia(asset, visiblePlanIds, scope))
                .filter(this::hasClusterCoordinates)
                .toList();
        Map<Long, TravelMediaAsset> mediaAssetById = photoMediaItems.stream()
                .collect(Collectors.toMap(TravelMediaAsset::getId, Function.identity(), (left, right) -> left));
        return new TravelMapSharePhotoData(buildMyMapPhotoClusters(photoMediaItems), mediaAssetById);
    }

    private boolean isVisibleTravelMapShareMedia(TravelMediaAsset mediaAsset, Set<Long> visiblePlanIds, TravelMapShareScope scope) {
        if (mediaAsset == null || mediaAsset.getPlan() == null || mediaAsset.getRecord() == null) {
            return false;
        }
        TravelExpenseRecord record = mediaAsset.getRecord();
        return visiblePlanIds.contains(mediaAsset.getPlan().getId())
                && mediaAsset.getMediaType() == TravelMediaType.PHOTO
                && isMemoryRecord(record)
                && !scope.excludedRecordIds().contains(record.getId())
                && !scope.excludedMediaIds().contains(mediaAsset.getId())
                && (hasCoordinates(record) || hasClusterCoordinates(mediaAsset));
    }

    private List<Long> loadVisibleTravelMapSharePlanIds(TravelMapShareLink shareLink, TravelMapShareScope scope) {
        Long ownerId = shareLink.getOwner().getId();
        Map<Long, TravelPlan> ownedPlanById = travelPlanRepository.findAllById(scope.planIds()).stream()
                .filter(plan -> plan.getOwner() != null && ownerId.equals(plan.getOwner().getId()))
                .collect(Collectors.toMap(TravelPlan::getId, Function.identity(), (left, right) -> left));
        return scope.planIds().stream()
                .filter(ownedPlanById::containsKey)
                .toList();
    }

    private TravelMyMapPhotoClusterSummaryResponse toTravelMapSharePhotoClusterSummaryResponse(
            TravelPhotoClusterData.PhotoCluster cluster,
            String shareToken
    ) {
        TravelPhotoClusterData.PhotoPoint representative = cluster.representative();
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
                buildTravelMapShareMediaUrl(shareToken, representative.mediaId())
        );
    }

    private List<TravelMyMapPhotoPinResponse> toTravelMapSharePhotoPins(
            TravelPhotoClusterData.PhotoCluster cluster,
            Map<Long, TravelMediaAsset> mediaAssetById,
            String shareToken
    ) {
        return (cluster.members() == null ? List.<TravelPhotoClusterData.PhotoPoint>of() : cluster.members()).stream()
                .map(member -> toTravelMapSharePhotoPinResponse(member, cluster, mediaAssetById.get(member.mediaId()), shareToken))
                .filter(Objects::nonNull)
                .toList();
    }

    private TravelMyMapPhotoPinResponse toTravelMapSharePhotoPinResponse(
            TravelPhotoClusterData.PhotoPoint member,
            TravelPhotoClusterData.PhotoCluster cluster,
            TravelMediaAsset mediaAsset,
            String shareToken
    ) {
        if (member == null || mediaAsset == null) {
            return null;
        }
        return new TravelMyMapPhotoPinResponse(
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
                buildTravelMapShareMediaUrl(shareToken, mediaAsset.getId()),
                member.mediaId().equals(cluster.representative().mediaId()),
                member.representativeOverride()
        );
    }

    private TravelMyMapPhotoClusterDetailResponse toTravelMapSharePhotoClusterDetailResponse(
            TravelPhotoClusterData.PhotoCluster cluster,
            Map<Long, TravelMediaAsset> mediaAssetById,
            String shareToken
    ) {
        TravelMediaAsset representativeAsset = mediaAssetById.get(cluster.representative().mediaId());
        TravelMediaResponse representativePhoto = representativeAsset != null
                ? toMediaResponse(representativeAsset, buildTravelMapShareMediaUrl(shareToken, representativeAsset.getId()))
                : null;
        List<TravelMediaResponse> photos = cluster.members().stream()
                .map(member -> mediaAssetById.get(member.mediaId()))
                .filter(Objects::nonNull)
                .map(asset -> toMediaResponse(asset, buildTravelMapShareMediaUrl(shareToken, asset.getId())))
                .toList();

        return new TravelMyMapPhotoClusterDetailResponse(
                cluster.id(),
                cluster.representative().mediaId(),
                cluster.representative().recordId(),
                cluster.centroidLatitude(),
                cluster.centroidLongitude(),
                cluster.photoCount(),
                cluster.memoryCount(),
                cluster.maxDistanceMeters(),
                cluster.representative().representativeOverride(),
                representativePhoto,
                photos
        );
    }

    private String buildTravelMapShareMediaUrl(String shareToken, Long mediaId) {
        return "/api/travel/public/map-shares/" + shareToken + "/media/" + mediaId + "/content";
    }

    private List<Long> deserializeIdList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Long> values = objectMapper.readValue(json, new TypeReference<>() {
            });
            return normalizeIdList(values);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private List<Long> normalizeIdList(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private Set<Long> toIdSet(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<TravelPhotoClusterData.PhotoCluster> buildMyMapPhotoClusters(List<TravelMediaAsset> photoMediaItems) {
        List<TravelPhotoClusterData.PhotoPoint> points = photoMediaItems.stream()
                .map(this::toPhotoClusterPoint)
                .toList();
        return travelPhotoClusterPolicy.cluster(points);
    }

    private TravelPhotoClusterData.PhotoPoint toPhotoClusterPoint(TravelMediaAsset mediaAsset) {
        return TravelClusterPhotoMapper.point(mediaAsset);
    }

    private boolean hasClusterCoordinates(TravelMediaAsset mediaAsset) {
        return TravelClusterPhotoMapper.hasCoordinates(mediaAsset);
    }

    private TravelMyMapMarkerSummaryResponse toMyMapMarkerSummaryResponse(TravelExpenseRecord record, String photoUrl) {
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
                photoUrl,
                record.getLatitude(),
                record.getLongitude()
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

    private TravelRouteSegmentResponse toMyMapRouteResponse(TravelRouteSegment routeSegment) {
        return TravelRouteMapper.response(TravelRouteMapper.result(routeSegment,routePayload,true));
    }

    private TravelMediaResponse toMediaResponse(TravelMediaAsset mediaAsset) {
        return toMediaResponse(mediaAsset, "/api/travel/media/" + mediaAsset.getId() + "/content");
    }

    private String normalizeColorHex(String value) {
        return TravelPlanPolicy.color(value);
    }

    private List<TravelRoutePointResponse> resolveRoutePoints(TravelRouteSegment route) {
        return TravelRouteMapper.points(routePayload.resolve(route.getSourceType(),route.getGpxFilesJson(),route.getRoutePathJson()));
    }

    private BigDecimal sumDistanceKm(Collection<TravelRouteSegment> routes) {
        return routes.stream()
                .map(TravelRouteSegment::getDistanceKm)
                .filter(value -> value != null)
                .reduce(ZERO_DISTANCE, BigDecimal::add)
                .setScale(3, RoundingMode.HALF_UP);
    }

    private boolean isMemoryRecord(TravelExpenseRecord record) {
        return TravelRecordPolicy.matches(record.getRecordType(), TravelRecordType.MEMORY);
    }

    private boolean hasCoordinates(TravelExpenseRecord record) {
        return record.getLatitude() != null && record.getLongitude() != null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private TravelMediaResponse toMediaResponse(TravelMediaAsset mediaAsset, String contentUrl) {
        return TravelClusterDetailMapper.response(TravelClusterDetailMapper.media(mediaAsset), contentUrl);
    }
    public record MediaDownload(String storagePath, String contentType, String fileName) {}
}
