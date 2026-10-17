package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.application.port.out.TravelRoutePayloadPort;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.*;
import com.playdata.calen.travel.domain.policy.TravelPlanPolicy;
import com.playdata.calen.travel.dto.TravelPlanDetailResponse;
import java.math.*;
import java.util.*;
import java.util.function.Function;

/** Detached detail projection shared by owned and authorized exhibit reads. No permission is granted here. */
public final class TravelPlanDetailMapper {
    private TravelPlanDetailMapper() {}
    public static final Comparator<TravelBudgetItem> BUDGET_ORDER = Comparator
            .comparingInt((TravelBudgetItem item) -> item.getDisplayOrder() != null ? item.getDisplayOrder() : 0).thenComparing(TravelBudgetItem::getId);
    public static final Comparator<TravelExpenseRecord> RECORD_ORDER = Comparator
            .comparing(TravelExpenseRecord::getExpenseDate, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(TravelExpenseRecord::getExpenseTime, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(TravelExpenseRecord::getId, Comparator.reverseOrder());
    public static final Comparator<TravelMediaAsset> MEDIA_ORDER = Comparator
            .comparing(TravelMediaAsset::getUploadedAt, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(TravelMediaAsset::getId, Comparator.reverseOrder());
    public static final Comparator<TravelRouteSegment> ROUTE_ORDER = Comparator
            .comparing(TravelRouteSegment::getRouteDate, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(TravelRouteSegment::getId, Comparator.reverseOrder());

    public static TravelPlanDetailData.Detail snapshot(TravelPlan plan, List<TravelBudgetItem> budgets, List<TravelExpenseRecord> records,
            List<TravelExpenseRecord> memories, List<TravelMediaAsset> media, List<TravelRouteSegment> routes, TravelRoutePayloadPort payload) {
        var metrics = new TravelPlanData.Metrics(sum(budgets, TravelBudgetItem::getAmountKrw, 2), sum(records, TravelExpenseRecord::getAmountKrw, 2),
                budgets.size(), records.size(), memories.size(), media.size(), routes.size(), sum(routes, TravelRouteSegment::getDistanceKm, 3),
                sumInt(routes, TravelRouteSegment::getDurationMinutes), sumInt(routes, TravelRouteSegment::getStepCount));
        var value = new TravelPlanData.Value(plan.getName(), plan.getDestination(), plan.getStartDate(), plan.getEndDate(), plan.getHomeCurrency(),
                plan.getHeadCount(), TravelPlanPolicy.status(plan.getStatus()), TravelPlanPolicy.color(plan.getColorHex()), plan.getMemo());
        return new TravelPlanDetailData.Detail(new TravelPlanData.Summary(plan.getId(), value, metrics), Boolean.TRUE.equals(plan.getPublicShared()), plan.getPublicSharedAt(),
                budgets.stream().sorted(BUDGET_ORDER).map(item -> new TravelBudgetData.Result(item.getId(), new TravelBudgetData.Value(item.getCategory(), item.getTitle(),
                        item.getAmount(), item.getCurrencyCode(), item.getExchangeRateToKrw(), item.getAmountKrw(), item.getMemo(), item.getDisplayOrder()))).toList(),
                records.stream().sorted(RECORD_ORDER).map(TravelPlanDetailMapper::record).toList(),
                memories.stream().sorted(RECORD_ORDER).map(TravelPlanDetailMapper::record).toList(),
                media.stream().sorted(MEDIA_ORDER).map(TravelClusterDetailMapper::media).toList(),
                routes.stream().sorted(ROUTE_ORDER).map(route -> TravelRouteMapper.result(route, payload, false)).toList());
    }

    public static TravelPlanDetailResponse response(TravelPlanDetailData.Detail detail, Function<Long, String> mediaUrl) {
        var summary = TravelPlanMapper.response(detail.summary());
        return new TravelPlanDetailResponse(summary.id(), summary.name(), summary.destination(), summary.startDate(), summary.endDate(), summary.homeCurrency(),
                summary.headCount(), summary.status(), summary.colorHex(), summary.memo(), summary.plannedTotalKrw(), summary.actualTotalKrw(), summary.budgetItemCount(),
                summary.recordCount(), summary.memoryRecordCount(), summary.mediaItemCount(), summary.routeSegmentCount(), summary.totalDistanceKm(),
                summary.totalDurationMinutes(), summary.totalStepCount(), detail.publicShared(), detail.publicSharedAt(),
                detail.budgets().stream().map(TravelBudgetMapper::response).toList(), detail.records().stream().map(TravelRecordMapper::expense).toList(),
                detail.memories().stream().map(TravelRecordMapper::memory).toList(),
                detail.media().stream().map(media -> TravelClusterDetailMapper.response(media, mediaUrl.apply(media.id()))).toList(),
                detail.routes().stream().map(TravelRouteMapper::response).toList());
    }

    private static TravelRecordData.Result record(TravelExpenseRecord record) {
        var plan = record.getPlan();
        return new TravelRecordData.Result(record.getId(), new TravelRecordData.Plan(plan.getId(), plan.getName(), TravelPlanPolicy.color(plan.getColorHex())),
                new TravelRecordData.Value(record.getRecordType(), record.getExpenseDate(), record.getExpenseTime(), record.getCategory(), record.getTitle(), record.getAmount(),
                        record.getCurrencyCode(), record.getExchangeRateToKrw(), record.getAmountKrw(), record.getCountry(), record.getRegion(), record.getPlaceName(),
                        record.getLatitude(), record.getLongitude(), Boolean.TRUE.equals(record.getSharedWithCommunity()), record.getMemo()));
    }

    private static <T> BigDecimal sum(Collection<T> items, Function<T, BigDecimal> value, int scale) {
        return items.stream().map(value).filter(Objects::nonNull).reduce(BigDecimal.ZERO.setScale(scale), BigDecimal::add).setScale(scale, RoundingMode.HALF_UP);
    }
    private static <T> int sumInt(Collection<T> items, Function<T, Integer> value) {
        return items.stream().map(value).filter(Objects::nonNull).reduce(0, Integer::sum);
    }
}
