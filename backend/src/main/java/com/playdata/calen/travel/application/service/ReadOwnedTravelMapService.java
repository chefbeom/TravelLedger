package com.playdata.calen.travel.application.service;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.TravelRouteLineStyle;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.*;
import com.playdata.calen.travel.domain.model.TravelRouteData;
import com.playdata.calen.travel.domain.policy.*;
import java.math.*;
import java.util.*;

/** Pure read workflow. The input adapter supplies the single-flight, wait and transaction boundaries. */
public final class ReadOwnedTravelMapService {
    private final TravelOwnedMapReadStore store;
    private final TravelOwnedMapCachePort cache;
    private final TravelOwnedMapClusterPort clusters;
    private final TravelRoutePayloadPort payload;
    public ReadOwnedTravelMapService(TravelOwnedMapReadStore store, TravelOwnedMapCachePort cache,
            TravelOwnedMapClusterPort clusters, TravelRoutePayloadPort payload) {
        this.store = store; this.cache = cache; this.clusters = clusters; this.payload = payload;
    }
    public Overview overview(Long owner, Long generation) {
        store.requireActiveOwner(owner);
        var cached = cache.get(owner, generation);
        if (cached != null) return cached;
        int plans = store.planCount(owner);
        var markers = store.markers(owner);
        var photos = clusters.getOrBuild(owner, generation);
        var routes = store.routes(owner);
        var distance = routes.stream().map(route -> route.value().distanceKm()).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO.setScale(3), BigDecimal::add).setScale(3, RoundingMode.HALF_UP);
        var response = new Overview(plans, markers.size(), photos.markerCount(), photos.clusterCount(), routes.size(), distance,
                markers.stream().map(marker -> new MarkerSummary(normalized(marker.marker()), marker.photoUrl())).toList(),
                photos.clusters(), photos.pins(), routes.stream().map(this::route).toList());
        cache.publish(owner, generation, response);
        return response;
    }
    public Bundle markerBundle(Long owner, Long id) {
        store.requireActiveOwner(owner);
        var selected = store.selectedMarker(owner, id);
        if (selected.latitude() == null || selected.longitude() == null) {
            throw new BadRequestException("Location coordinates are required for map detail loading.");
        }
        var records = new ArrayList<Location>(); records.add(selected); records.addAll(nearest(owner, selected));
        return new Bundle(selected.id(), store.details(records));
    }
    private List<Location> nearest(Long owner, Location selected) {
        double radius = 100d;
        while (true) {
            var bounds = TravelMapGeometry.around(selected.latitude().doubleValue(), selected.longitude().doubleValue(), radius);
            var candidates = store.nearby(owner, selected, bounds, 10).stream()
                    .sorted(Comparator.comparingDouble(marker -> TravelMapGeometry.distance(selected, marker))).toList();
            if (radius >= TravelMapGeometry.MAX_RADIUS_METERS || (candidates.size() >= 10 && TravelMapGeometry.distance(selected, candidates.get(9)) <= radius)) {
                return candidates;
            }
            radius = Math.min(TravelMapGeometry.MAX_RADIUS_METERS, radius * 10);
        }
    }
    public List<Frame> frames(Long owner) { store.requireActiveOwner(owner); return store.frames(owner); }
    private Marker normalized(Marker value) {
        return new Marker(value.id(), value.planId(), value.planName(), TravelPlanPolicy.color(value.colorHex()), value.date(), value.time(), value.category(),
                value.title(), value.country(), value.region(), value.placeName(), value.latitude(), value.longitude());
    }
    private TravelRouteData.Result route(Route row) {
        var value = row.value(); var plan = row.plan();
        var names = payload.files(row.gpxFiles()).stream().map(TravelRouteData.GpxFile::originalFileName).toList();
        return new TravelRouteData.Result(row.id(), new TravelRouteData.Plan(plan.id(), plan.name(), TravelPlanPolicy.color(plan.colorHex())),
                value.routeDate(), value.title(), value.transportMode(), value.distanceKm() == null ? BigDecimal.ZERO.setScale(3) : value.distanceKm().setScale(3, RoundingMode.HALF_UP),
                value.durationMinutes(), value.stepCount(), value.sourceType(), value.startPlaceName(), value.endPlaceName(), TravelPlanPolicy.color(value.lineColorHex()),
                value.lineStyle() == null ? TravelRouteLineStyle.SOLID : value.lineStyle(), names, value.memo(), payload.decode(value.path()));
    }
}
