package com.playdata.calen.travel.application.service;

import com.playdata.calen.common.exception.*;
import com.playdata.calen.travel.application.port.in.TravelMapLinksUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.TravelRouteLineStyle;
import com.playdata.calen.travel.domain.TravelMediaType;
import com.playdata.calen.travel.domain.model.TravelMapLinkData.*;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.Marker;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.Route;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData;
import com.playdata.calen.travel.domain.model.TravelMediaData.Download;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.*;
import com.playdata.calen.travel.domain.model.TravelRouteData;
import com.playdata.calen.travel.domain.policy.*;
import java.math.*;
import java.util.*;
import java.util.stream.Collectors;
import static com.playdata.calen.travel.domain.policy.TravelMapLinkPolicy.*;

/** Link scope is re-evaluated on every read. It deliberately does not use the owner map cache. */
public final class TravelMapLinksService implements TravelMapLinksUseCase {
    private final TravelMapLinkStore store;
    private final TravelMapLinkRuntimePort runtime;
    private final TravelRoutePayloadPort payload;
    private final TravelPhotoClusterPolicy clustering;
    public TravelMapLinksService(TravelMapLinkStore store, TravelMapLinkRuntimePort runtime, TravelRoutePayloadPort payload, TravelPhotoClusterPolicy clustering) {
        this.store = store; this.runtime = runtime; this.payload = payload; this.clustering = clustering;
    }
    @Override public Link create(Long owner, Draft draft) {
        store.requireActiveOwner(owner);
        var plans = ids(draft.plans());
        if (plans.isEmpty()) throw new BadRequestException("At least one travel plan is required.");
        if (store.ownedPlans(owner, plans).size() != plans.size()) throw new BadRequestException("Only owned travel plans can be shared.");
        var planSet = new HashSet<>(plans);
        var records = new LinkedHashSet<>(ids(draft.records()));
        var media = new LinkedHashSet<>(ids(draft.media()));
        var routes = new LinkedHashSet<>(ids(draft.routes()));
        var validRecords = records.isEmpty() ? Set.<Long>of() : store.ownedRecords(owner, planSet, records);
        if (validRecords.size() != records.size()) throw new BadRequestException("Excluded records must belong to selected travel plans.");
        var validMedia = media.isEmpty() ? Set.<Long>of() : store.ownedMedia(owner, planSet, media);
        if (validMedia.size() != media.size()) throw new BadRequestException("Excluded media must belong to selected travel plans.");
        var validRoutes = routes.isEmpty() ? Set.<Long>of() : store.ownedRoutes(owner, planSet, routes);
        if (validRoutes.size() != routes.size()) throw new BadRequestException("Excluded routes must belong to selected travel plans.");
        var now = runtime.now();
        return store.save(owner, runtime.uniqueToken(), title(draft.title()), new Scope(plans, validRecords, validMedia, validRoutes), now);
    }
    private Link active(String token) {
        var normalized = text(token);
        if (normalized == null) throw new NotFoundException("Shared travel map not found.");
        return store.active(normalized);
    }
    private List<Long> plans(Link link) {
        var visible = store.ownedPlans(link.owner(), link.scope().plans());
        return link.scope().plans().stream().filter(visible::contains).toList();
    }
    private List<Marker> markers(List<Long> plans, Scope scope, int limit) {
        if (plans.isEmpty()) return List.of();
        return store.markers(plans).stream().filter(marker -> marker.latitude() != null && marker.longitude() != null)
                .filter(marker -> !scope.records().contains(marker.id())).limit(limit).toList();
    }
    @Override public PublicMap map(String token) {
        var link = active(token); var scope = link.scope(); var plans = plans(link);
        if (plans.isEmpty()) return new PublicMap(link, new Overview(
                0, 0, BigDecimal.ZERO.setScale(3), List.of(), List.of(), List.of(), List.of()));
        var markers = markers(plans, scope, Integer.MAX_VALUE);
        var photoIds = new HashMap<Long, Long>();
        if (!markers.isEmpty()) store.markerPhotos(markers.stream().map(Marker::id).toList()).stream()
                .filter(asset -> asset.mediaType() == TravelMediaType.PHOTO)
                .filter(asset -> !scope.media().contains(asset.id())).forEach(asset -> photoIds.putIfAbsent(asset.recordId(), asset.id()));
        var routes = store.routes(plans).stream().filter(route -> !scope.routes().contains(route.id())).toList();
        var photos = store.photos(new HashSet<>(plans), scope);
        var mediaIds = photos.stream().map(PhotoPoint::mediaId).collect(Collectors.toSet());
        var clusters = clustering.cluster(photos);
        var distance = routes.stream().map(route -> route.value().distanceKm()).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO.setScale(3), BigDecimal::add).setScale(3, RoundingMode.HALF_UP);
        var resultMarkers = markers.stream().map(marker -> new MarkerPhoto(normalized(marker), photoIds.get(marker.id()))).toList();
        var summaries = clusters.stream().map(this::summary).toList();
        var pins = clusters.stream().flatMap(cluster -> (cluster.members() == null ? List.<PhotoPoint>of() : cluster.members()).stream()
                .filter(Objects::nonNull).filter(member -> mediaIds.contains(member.mediaId())).map(member -> pin(member, cluster))).toList();
        var resultRoutes = routes.stream().map(this::snapshot).toList();
        return new PublicMap(link, new Overview(
                plans.size(), mediaIds.size(), distance, resultMarkers, summaries, pins, resultRoutes));
    }
    @Override public Cluster cluster(String token, Long id, Integer page, Integer size, Long focus) {
        var link = active(token);
        var photos = store.photos(new HashSet<>(plans(link)), link.scope());
        var cluster = clustering.cluster(photos).stream().filter(value -> value.id() != null && value.id().equals(id))
                .findFirst().orElseThrow(() -> new NotFoundException("Shared travel photo cluster not found."));
        var ids = new ArrayList<Long>(); ids.add(cluster.representative().mediaId());
        cluster.members().forEach(member -> ids.add(member.mediaId()));
        var details = store.details(ids);
        var detail = new TravelClusterDetailData.Detail(cluster.id(), cluster.representative().mediaId(), cluster.representative().recordId(),
                cluster.centroidLatitude(), cluster.centroidLongitude(), cluster.photoCount(), cluster.memoryCount(), cluster.maxDistanceMeters(),
                cluster.representative().representativeOverride(), details.get(0), details.subList(1, details.size()));
        var slice = TravelClusterPagePolicy.slice(detail.photos(), detail.representativeMediaId(), focus, page, size,
                TravelClusterDetailData.Media::id, TravelClusterDetailData.Media::expenseDate, TravelClusterDetailData.Media::expenseTime, TravelClusterDetailData.Media::uploadedAt);
        return new Cluster(link.token(), new TravelClusterDetailData.Page(detail, slice.photos(), slice.page(), slice.size(), slice.total(), slice.hasNext()));
    }
    @Override public Download media(String token, Long id) {
        var link = active(token); var plans = new HashSet<>(plans(link)); var asset = store.asset(id);
        if (!visible(asset, plans, link.scope())) throw new NotFoundException("Shared travel media not found.");
        return asset.download();
    }
    @Override public Preview preview(String token) {
        var link = active(token); var scope = link.scope(); var plans = plans(link);
        if (plans.isEmpty()) return Preview.empty();
        var markers = markers(plans, scope, PREVIEW_MARKERS);
        var planSet = new HashSet<>(plans); var photoIds = new HashSet<Long>();
        if (!markers.isEmpty()) store.markerPhotos(markers.stream().map(Marker::id).toList()).stream()
                .filter(asset -> visible(asset, planSet, scope)).forEach(asset -> photoIds.add(asset.recordId()));
        var previewMarkers = new ArrayList<PreviewMarker>();
        for (var marker : markers) previewMarkers.add(new PreviewMarker(previewMarkers.size() + 1, rounded(marker.latitude()), rounded(marker.longitude()), photoIds.contains(marker.id())));
        var routes = store.routes(plans).stream().filter(route -> !scope.routes().contains(route.id())).limit(PREVIEW_ROUTES).toList();
        var previewRoutes = new ArrayList<PreviewRoute>();
        for (var route : routes) {
            var value = route.value();
            var points = sample(payload.resolve(value.sourceType(), route.gpxFiles(), value.path()));
            if (points.size() >= 2) previewRoutes.add(new PreviewRoute(previewRoutes.size() + 1, points));
        }
        return new Preview(true, previewMarkers, previewRoutes);
    }
    @Override public Download previewMedia(String token, int number) {
        if (number < 1 || number > PREVIEW_MARKERS) throw new NotFoundException("Login map preview thumbnail not found.");
        var link = active(token); var plans = plans(link);
        if (plans.isEmpty()) throw new NotFoundException("Login map preview thumbnail not found.");
        var markers = markers(plans, link.scope(), PREVIEW_MARKERS);
        if (markers.size() < number) throw new NotFoundException("Login map preview thumbnail not found.");
        var planSet = new HashSet<>(plans);
        return store.markerPhotos(List.of(markers.get(number - 1).id())).stream().filter(asset -> visible(asset, planSet, link.scope()))
                .findFirst().map(Asset::download).orElseThrow(() -> new NotFoundException("Login map preview thumbnail not found."));
    }
    private Marker normalized(Marker m) {
        return new Marker(m.id(), m.planId(), m.planName(), TravelPlanPolicy.color(m.colorHex()), m.date(), m.time(), m.category(), m.title(), m.country(), m.region(), m.placeName(), m.latitude(), m.longitude());
    }
    private Summary summary(PhotoCluster cluster) {
        var p = cluster.representative();
        return new Summary(cluster.id(), p.mediaId(), p.recordId(), p.planId(), p.planName(), TravelPlanPolicy.color(p.planColorHex()), p.memoryDate(), p.memoryTime(),
                p.category(), p.title(), p.country(), p.region(), p.placeName(), p.latitude(), p.longitude(), cluster.photoCount(), cluster.memoryCount(), cluster.maxDistanceMeters(), p.representativeOverride());
    }
    private Pin pin(PhotoPoint p, PhotoCluster cluster) {
        return new Pin(p.mediaId(), cluster.id(), p.recordId(), p.planId(), p.planName(), TravelPlanPolicy.color(p.planColorHex()), p.memoryDate(), p.memoryTime(),
                p.category(), p.title(), p.country(), p.region(), p.placeName(), p.latitude(), p.longitude(), p.mediaId().equals(cluster.representative().mediaId()), p.representativeOverride());
    }
    private TravelRouteData.Result snapshot(Route route) {
        var value = route.value(); var plan = route.plan();
        var names = payload.files(route.gpxFiles()).stream().map(TravelRouteData.GpxFile::originalFileName).toList();
        return new TravelRouteData.Result(route.id(), new TravelRouteData.Plan(plan.id(), plan.name(), TravelPlanPolicy.color(plan.colorHex())),
                value.routeDate(), value.title(), value.transportMode(), value.distanceKm() == null ? BigDecimal.ZERO.setScale(3) : value.distanceKm().setScale(3, RoundingMode.HALF_UP),
                value.durationMinutes(), value.stepCount(), value.sourceType(), value.startPlaceName(), value.endPlaceName(), TravelPlanPolicy.color(value.lineColorHex()),
                value.lineStyle() == null ? TravelRouteLineStyle.SOLID : value.lineStyle(), names, value.memo(), payload.decode(value.path()));
    }
}
