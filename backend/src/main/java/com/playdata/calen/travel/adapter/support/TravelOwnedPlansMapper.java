package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.domain.model.*;
import com.playdata.calen.travel.domain.model.TravelOwnedPlansData.*;
import com.playdata.calen.travel.dto.*;
import java.util.List;
import java.util.function.Function;

/** HTTP projection and exact old-cache decoding; cache hits do not reapply current business defaults. */
public final class TravelOwnedPlansMapper {
    private TravelOwnedPlansMapper() {}
    public static TravelPlanSummaryResponse summary(Summary s) {
        if (s == null) return null;
        var h = s.head(); var m = s.metrics();
        return new TravelPlanSummaryResponse(h.id(), h.name(), h.destination(), h.startDate(), h.endDate(), h.currency(), h.headCount(), h.status(), h.color(), h.memo(),
                m.plannedTotalKrw(), m.actualTotalKrw(), m.budgetItemCount(), m.recordCount(), m.memoryRecordCount(), m.mediaItemCount(), m.routeSegmentCount(), m.totalDistanceKm(), m.totalDurationMinutes(), m.totalStepCount());
    }
    public static Summary summary(TravelPlanSummaryResponse s) {
        if (s == null) return null;
        return new Summary(new Head(s.id(), s.name(), s.destination(), s.startDate(), s.endDate(), s.homeCurrency(), s.headCount(), s.status(), s.colorHex(), s.memo()),
                new TravelPlanData.Metrics(s.plannedTotalKrw(), s.actualTotalKrw(), s.budgetItemCount(), s.recordCount(), s.memoryRecordCount(), s.mediaItemCount(), s.routeSegmentCount(), s.totalDistanceKm(), s.totalDurationMinutes(), s.totalStepCount()));
    }
    public static TravelPortfolioResponse portfolio(Portfolio p) {
        var m = p.metrics();
        return new TravelPortfolioResponse(p.scopeType(), p.scopeLabel(), p.name(), p.currency(), p.headCount(), m.plannedTotalKrw(), m.actualTotalKrw(), m.budgetItemCount(), m.recordCount(), m.memoryRecordCount(),
                m.mediaItemCount(), m.routeSegmentCount(), m.totalDistanceKm(), m.totalDurationMinutes(), m.totalStepCount(), p.planCount(), map(p.plans(), TravelOwnedPlansMapper::summary),
                map(p.budgets(), TravelBudgetMapper::response), map(p.records(), TravelRecordMapper::expense), map(p.memories(), TravelOwnedPlansMapper::memory),
                map(p.media(), v -> TravelClusterDetailMapper.response(v.value(), v.ownedUrl() ? "/api/travel/media/" + v.value().id() + "/content" : v.url())),
                map(p.routes(), TravelOwnedPlansMapper::route));
    }
    public static Portfolio portfolio(TravelPortfolioResponse p) {
        if (p == null) return null;
        return new Portfolio(p.scopeType(), p.scopeLabel(), p.name(), p.homeCurrency(), p.headCount(), new TravelPlanData.Metrics(p.plannedTotalKrw(), p.actualTotalKrw(), p.budgetItemCount(), p.recordCount(),
                p.memoryRecordCount(), p.mediaItemCount(), p.routeSegmentCount(), p.totalDistanceKm(), p.totalDurationMinutes(), p.totalStepCount()), p.includedPlanCount(),
                map(p.plans(), TravelOwnedPlansMapper::summary), map(p.budgetItems(), v -> new TravelBudgetData.Result(v.id(), new TravelBudgetData.Value(v.category(), v.title(), v.amount(), v.currencyCode(), v.exchangeRateToKrw(), v.amountKrw(), v.memo(), v.displayOrder()))),
                map(p.records(), TravelOwnedPlansMapper::expense), map(p.memoryRecords(), TravelOwnedPlansMapper::memory), map(p.mediaItems(), TravelOwnedPlansMapper::picture),
                map(p.routeSegments(), TravelOwnedPlansMapper::route));
    }
    private static TravelRecordData.Result expense(TravelExpenseRecordResponse r) {
        return new TravelRecordData.Result(r.id(), new TravelRecordData.Plan(r.planId(), r.planName(), r.planColorHex()), new TravelRecordData.Value(r.recordType(), r.expenseDate(), r.expenseTime(), r.category(), r.title(), r.amount(), r.currencyCode(), r.exchangeRateToKrw(), r.amountKrw(), r.country(), r.region(), r.placeName(), r.latitude(), r.longitude(), false, r.memo()));
    }
    private static Memory memory(TravelMemoryRecordResponse r) {
        return new Memory(new TravelRecordData.Result(r.id(), new TravelRecordData.Plan(r.planId(), r.planName(), r.planColorHex()), new TravelRecordData.Value(r.recordType(), r.memoryDate(), r.memoryTime(), r.category(), r.title(), null, null, null, null, r.country(), r.region(), r.placeName(), r.latitude(), r.longitude(), Boolean.TRUE.equals(r.sharedWithCommunity()), r.memo())), r.sharedWithCommunity());
    }
    private static TravelMemoryRecordResponse memory(Memory m) {
        var r = m.value(); var p = r.plan(); var v = r.value();
        return new TravelMemoryRecordResponse(r.id(), p.id(), p.name(), p.colorHex(), v.type(), v.date(), v.time(), v.category(), v.title(), v.country(), v.region(), v.placeName(), v.latitude(), v.longitude(), m.shared(), v.memo());
    }
    private static Picture picture(TravelMediaResponse m) {
        return new Picture(new TravelClusterDetailData.Media(m.id(), m.planId(), m.planName(), m.planColorHex(), m.recordId(), m.recordType(), m.mediaType(), m.originalFileName(), m.contentType(), m.fileSize(), m.caption(), m.uploadedBy(), m.uploadedAt(), m.expenseDate(), m.expenseTime(), m.title(), m.amount(), m.currencyCode(), m.amountKrw(), m.country(), m.region(), m.placeName(), m.latitude(), m.longitude(), m.gpsLatitude(), m.gpsLongitude(), m.representativeOverride()), m.contentUrl(), false);
    }
    private static TravelRouteData.Result route(TravelRouteSegmentResponse r) {
        return new TravelRouteData.Result(r.id(), new TravelRouteData.Plan(r.planId(), r.planName(), r.planColorHex()), r.routeDate(), r.title(), r.transportMode(), r.distanceKm(), r.durationMinutes(), r.stepCount(), r.sourceType(), r.startPlaceName(), r.endPlaceName(), r.lineColorHex(), r.lineStyle(), r.gpxFileNames(), r.memo(), map(r.points(), p -> new TravelRouteData.Point(p.latitude(), p.longitude(), p.pointType(), p.linkedMemoryId(), p.label())));
    }
    private static TravelRouteSegmentResponse route(TravelRouteData.Result r) {
        return new TravelRouteSegmentResponse(r.id(), r.plan().id(), r.plan().name(), r.plan().colorHex(), r.routeDate(), r.title(), r.transportMode(), r.distanceKm(), r.durationMinutes(), r.stepCount(), r.sourceType(), r.startPlaceName(), r.endPlaceName(), r.lineColorHex(), r.lineStyle(), r.gpxFileNames(), r.memo(), map(r.points(), p -> new TravelRoutePointResponse(p.latitude(), p.longitude(), p.pointType(), p.linkedMemoryId(), p.label())));
    }
    private static <S, T> List<T> map(List<S> source, Function<S, T> mapper) { return source == null ? null : source.stream().map(v -> v == null ? null : mapper.apply(v)).toList(); }
}
