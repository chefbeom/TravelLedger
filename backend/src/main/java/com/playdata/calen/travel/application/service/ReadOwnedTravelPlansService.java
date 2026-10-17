package com.playdata.calen.travel.application.service;

import com.playdata.calen.travel.application.port.in.ReadOwnedTravelPlansUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.model.*;
import com.playdata.calen.travel.domain.model.TravelOwnedPlansData.*;
import com.playdata.calen.travel.domain.policy.*;
import java.math.*;
import java.util.List;

public final class ReadOwnedTravelPlansService implements ReadOwnedTravelPlansUseCase {
    private final TravelOwnedPlansStore store;
    private final TravelOwnedPlansCachePort cache;
    private final TravelRoutePayloadPort payload;
    public ReadOwnedTravelPlansService(TravelOwnedPlansStore store, TravelOwnedPlansCachePort cache, TravelRoutePayloadPort payload) {
        this.store = store; this.cache = cache; this.payload = payload;
    }
    @Override public List<Summary> plans(Long owner) {
        store.requireActiveOwner(owner);
        var cached = cache.plans(owner); if (cached != null) return cached;
        var result = store.aggregates(owner).stream().map(TravelOwnedPlansPolicy::aggregate).toList();
        cache.publishPlans(owner, result); return result;
    }
    @Override public TravelPlanDetailData.Detail detail(Long owner, Long plan) {
        store.requireActiveOwner(owner); return store.detail(owner, plan);
    }
    @Override public Portfolio portfolio(Long owner) {
        store.requireActiveOwner(owner);
        var cached = cache.portfolio(owner); if (cached != null) return cached;
        var rows = store.portfolio(owner);
        var summaries = TravelOwnedPlansPolicy.summaries(rows);
        var metrics = TravelOwnedPlansPolicy.metrics(rows);
        var budgets = rows.budgets().stream().map(Budget::value).toList();
        var records = rows.records().stream().map(this::record).toList();
        var memories = rows.memories().stream().map(r -> new Memory(record(r), r.value().shared())).toList();
        var media = rows.media().stream().map(v -> new Picture(picture(v), null, true)).toList();
        var routes = rows.routes().stream().map(this::route).toList();
        var result = new Portfolio("ALL", "All trips", "All trips", "KRW", 1, metrics, rows.plans().size(), summaries, budgets, records, memories, media, routes);
        cache.publishPortfolio(owner, result); return result;
    }
    private TravelRecordData.Result record(TravelRecordData.Result r) {
        var p = r.plan(); return new TravelRecordData.Result(r.id(), new TravelRecordData.Plan(p.id(), p.name(), TravelPlanPolicy.color(p.colorHex())), r.value());
    }
    private TravelClusterDetailData.Media picture(TravelClusterDetailData.Media m) {
        return new TravelClusterDetailData.Media(m.id(), m.planId(), m.planName(), TravelPlanPolicy.color(m.planColorHex()), m.recordId(), m.recordType(), m.mediaType(),
                m.originalFileName(), m.contentType(), m.fileSize(), m.caption(), m.uploadedBy(), m.uploadedAt(), m.expenseDate(), m.expenseTime(), m.title(), m.amount(),
                m.currencyCode(), m.amountKrw(), m.country(), m.region(), m.placeName(), m.latitude(), m.longitude(), m.gpsLatitude(), m.gpsLongitude(), m.representativeOverride());
    }
    private TravelRouteData.Result route(TravelOwnedMapData.Route r) {
        var p = r.plan(); var v = r.value(); var color = TravelPlanPolicy.color(p.colorHex());
        var names = payload.files(r.gpxFiles()).stream().map(TravelRouteData.GpxFile::originalFileName).filter(n -> n != null && !n.isBlank()).toList();
        return new TravelRouteData.Result(r.id(), new TravelRouteData.Plan(p.id(), p.name(), color), v.routeDate(), v.title(), v.transportMode(),
                (v.distanceKm() == null ? BigDecimal.ZERO : v.distanceKm()).setScale(3, RoundingMode.HALF_UP), v.durationMinutes() == null ? 0 : v.durationMinutes(),
                v.stepCount(), v.sourceType(), v.startPlaceName(), v.endPlaceName(), TravelRoutePolicy.lineColor(v.lineColorHex(), p.colorHex()),
                v.lineStyle() == null ? com.playdata.calen.travel.domain.TravelRouteLineStyle.SOLID : v.lineStyle(), names, v.memo(),
                payload.resolve(v.sourceType(), r.gpxFiles(), v.path()));
    }
}
