package com.playdata.calen.travel.application.service;

import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.application.port.in.ReadTravelAtlasUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelAtlasData.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.Route;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData;
import com.playdata.calen.travel.domain.model.TravelRouteData;
import com.playdata.calen.travel.domain.policy.*;
import java.math.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.playdata.calen.travel.domain.policy.TravelAtlasPolicy.*;

/** Authenticated public atlas and community feed have intentionally different visibility rules. */
public final class ReadTravelAtlasService implements ReadTravelAtlasUseCase {
    private final TravelAtlasStore store;
    private final TravelAtlasMediaTokenPort tokens;
    private final TravelRoutePayloadPort payload;
    private final TravelPhotoClusterPolicy clustering;
    private static final Comparator<Photo> PHOTO_ORDER = Comparator.comparing((Photo p) -> p.point().uploadedAt(),
            Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Photo::id, Comparator.reverseOrder());
    private static final Comparator<FeedPhoto> FEED_PHOTO_ORDER = Comparator.comparing(FeedPhoto::uploadedAt,
            Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(FeedPhoto::id, Comparator.reverseOrder());
    public ReadTravelAtlasService(TravelAtlasStore store, TravelAtlasMediaTokenPort tokens, TravelRoutePayloadPort payload, TravelPhotoClusterPolicy clustering) {
        this.store = store; this.tokens = tokens; this.payload = payload; this.clustering = clustering;
    }
    private List<Access> accesses(Long viewer) { return publicFirst(store.publishedCompletedPlans(), store.receivedCompletedPlans(viewer)); }
    private Map<Long, Access> shares(List<Access> accesses) {
        return accesses.stream().filter(a -> a.share() != null).collect(Collectors.toMap(a -> a.plan().id(), Function.identity(), (left, right) -> left));
    }
    @Override public Overview overview(Long viewer) {
        store.requireActiveViewer(viewer);
        var accesses = accesses(viewer); var ids = accesses.stream().map(a -> a.plan().id()).toList();
        if (ids.isEmpty()) return new Overview(0, zero(), List.of(), List.of(), List.of(), List.of());
        var shares = shares(accesses);
        var memories = store.memories(ids); var photos = store.photos(ids); var routes = store.routes(ids);
        var clusters = clustering.cluster(photos.stream().map(Photo::point).toList());
        var byId = photos.stream().collect(Collectors.toMap(Photo::id, Function.identity()));
        var memoriesByPlan = memories.stream().collect(Collectors.groupingBy(m -> m.marker().planId()));
        var photosByPlan = photos.stream().collect(Collectors.groupingBy(p -> p.plan().id()));
        var routesByPlan = routes.stream().collect(Collectors.groupingBy(r -> r.plan().id()));
        var distance = distance(routes);
        var trips = accesses.stream().map(access -> trip(access, memoriesByPlan.getOrDefault(access.plan().id(), List.of()),
                photosByPlan.getOrDefault(access.plan().id(), List.of()), routesByPlan.getOrDefault(access.plan().id(), List.of()), shares)).toList();
        var summaries = clusters.stream().map(cluster -> summary(cluster, byId, shares)).toList();
        var pins = clusters.stream().flatMap(cluster -> (cluster.members() == null ? List.<TravelPhotoClusterData.PhotoPoint>of() : cluster.members()).stream()
                .map(member -> pin(member, cluster, byId.get(member.mediaId()), shares)).filter(Objects::nonNull)).toList();
        return new Overview(photos.size(), distance, trips, summaries, pins, routes.stream().map(this::route).toList());
    }
    @Override public Cluster cluster(Long viewer, Long id, Integer page, Integer size, Long focus) {
        store.requireActiveViewer(viewer);
        var accesses = accesses(viewer); var shares = shares(accesses);
        var byId = store.photos(accesses.stream().map(a -> a.plan().id()).toList()).stream().collect(Collectors.toMap(Photo::id, Function.identity()));
        // Preserve the old HashMap value iteration input used by the cluster-detail path.
        var cluster = clustering.cluster(new ArrayList<>(byId.values()).stream().map(Photo::point).toList()).stream()
                .filter(candidate -> candidate.id() != null && candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Public travel photo cluster not found."));
        var references = new HashMap<Long, Reference>();
        var representative = detail(byId.get(cluster.representative().mediaId()), shares, references);
        var photos = cluster.members().stream().map(member -> byId.get(member.mediaId())).filter(Objects::nonNull)
                .map(photo -> detail(photo, shares, references)).toList();
        var header = new TravelClusterDetailData.Detail(cluster.id(), cluster.representative().mediaId(), cluster.representative().recordId(),
                cluster.representative().latitude(), cluster.representative().longitude(), cluster.photoCount(), cluster.memoryCount(), cluster.maxDistanceMeters(),
                cluster.representative().representativeOverride(), representative, photos);
        var slice = TravelClusterPagePolicy.slice(photos, header.representativeMediaId(), focus, page, size,
                TravelClusterDetailData.Media::id, TravelClusterDetailData.Media::expenseDate, TravelClusterDetailData.Media::expenseTime, TravelClusterDetailData.Media::uploadedAt);
        return new Cluster(new TravelClusterDetailData.Page(header, slice.photos(), slice.page(), slice.size(), slice.total(), slice.hasNext()), references);
    }
    private TravelClusterDetailData.Media detail(Photo photo, Map<Long, Access> shares, Map<Long, Reference> references) {
        if (photo == null) return null;
        references.put(photo.id(), reference(photo, shares));
        return store.detail(photo.id());
    }
    @Override public Feed community(Long viewer, Integer requestedPage, Integer requestedSize) {
        store.requireActiveViewer(viewer);
        int page = page(requestedPage), size = size(requestedSize);
        var window = store.community(page, size);
        var records = window.items().stream().filter(m -> TravelRecordPolicy.matches(m.type(), TravelRecordType.MEMORY)).toList();
        if (records.isEmpty()) return new Feed(List.of(), page, size, window.total(), window.totalPages());
        var byRecord = store.communityPhotos(records.stream().map(m -> m.marker().id()).toList()).stream().collect(Collectors.groupingBy(FeedPhoto::recordId));
        var items = records.stream().filter(m -> !byRecord.getOrDefault(m.marker().id(), List.of()).isEmpty()).map(memory -> {
            var photos = byRecord.get(memory.marker().id());
            var hero = photos.stream().sorted(FEED_PHOTO_ORDER).findFirst().orElseThrow();
            var color = TravelPlanPolicy.color(memory.marker().colorHex());
            var info = store.hero(hero.id());
            return new Post(memory, color, info, new Reference(hero.id(), tokens.issue(hero.id()), null), photos.size());
        }).toList();
        return new Feed(items, page, size, window.total(), window.totalPages());
    }
    private Trip trip(Access access, List<Memory> memories, List<Photo> photos, List<Route> routes, Map<Long, Access> shares) {
        var hero = photos.stream().sorted(PHOTO_ORDER).findFirst().map(p -> reference(p, shares)).orElse(null);
        var plan = access.plan();
        var normalized = new Plan(plan.id(), plan.name(), plan.destination(), plan.start(), plan.end(), TravelPlanPolicy.status(plan.status()),
                TravelPlanPolicy.color(plan.color()), plan.owner(), plan.publicShared(), plan.publicAt());
        return new Trip(normalized, sharing(plan, access.share() == null ? null : access), memories.size(), photos.size(), routes.size(), distance(routes), hero);
    }
    private Summary summary(TravelPhotoClusterData.PhotoCluster cluster, Map<Long, Photo> byId, Map<Long, Access> shares) {
        var p = cluster.representative(); var photo = byId.get(p.mediaId());
        var row = new TravelPhotoClusterData.Summary(cluster.id(), p.mediaId(), p.recordId(), p.planId(), p.planName(), TravelPlanPolicy.color(p.planColorHex()),
                p.memoryDate(), p.memoryTime(), p.category(), p.title(), p.country(), p.region(), p.placeName(), p.latitude(), p.longitude(), cluster.photoCount(),
                cluster.memoryCount(), cluster.maxDistanceMeters(), p.representativeOverride());
        return new Summary(row, photo == null ? null : reference(photo, shares), sharing(photo == null ? null : photo.plan(), photo == null ? null : shares.get(photo.plan().id())));
    }
    private Pin pin(TravelPhotoClusterData.PhotoPoint p, TravelPhotoClusterData.PhotoCluster cluster, Photo photo, Map<Long, Access> shares) {
        if (p == null || photo == null) return null;
        var row = new TravelPhotoClusterData.Pin(p.mediaId(), cluster.id(), p.recordId(), p.planId(), p.planName(), TravelPlanPolicy.color(p.planColorHex()), p.memoryDate(),
                p.memoryTime(), p.category(), p.title(), p.country(), p.region(), p.placeName(), p.latitude(), p.longitude(), p.mediaId().equals(cluster.representative().mediaId()), p.representativeOverride());
        return new Pin(row, reference(photo, shares), sharing(photo.plan(), shares.get(photo.plan().id())));
    }
    private Reference reference(Photo photo, Map<Long, Access> shares) {
        if (Boolean.TRUE.equals(photo.plan().publicShared())) return new Reference(photo.id(), tokens.issue(photo.id()), null);
        var share = shares.get(photo.plan().id());
        if (share == null) throw new NotFoundException("Shared media not found.");
        return new Reference(photo.id(), null, share.share());
    }
    private TravelRouteData.Result route(Route row) {
        var value = row.value(); var plan = row.plan();
        var names = payload.files(row.gpxFiles()).stream().map(TravelRouteData.GpxFile::originalFileName).filter(name -> name != null && !name.isBlank()).toList();
        return new TravelRouteData.Result(row.id(), new TravelRouteData.Plan(plan.id(), plan.name(), TravelPlanPolicy.color(plan.colorHex())), value.routeDate(), value.title(),
                value.transportMode(), value.distanceKm() == null ? zero() : value.distanceKm().setScale(3, RoundingMode.HALF_UP), value.durationMinutes() == null ? 0 : value.durationMinutes(),
                value.stepCount(), value.sourceType(), value.startPlaceName(), value.endPlaceName(), TravelRoutePolicy.lineColor(value.lineColorHex(), plan.colorHex()),
                value.lineStyle() == null ? TravelRouteLineStyle.SOLID : value.lineStyle(), names, value.memo(), payload.resolve(value.sourceType(), row.gpxFiles(), value.path()));
    }
    private BigDecimal zero() { return BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP); }
    private BigDecimal distance(List<Route> routes) {
        return routes.stream().map(r -> r.value().distanceKm()).filter(Objects::nonNull).reduce(zero(), BigDecimal::add).setScale(3, RoundingMode.HALF_UP);
    }
}
