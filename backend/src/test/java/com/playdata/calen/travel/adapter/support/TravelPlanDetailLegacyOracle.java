package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.application.port.out.TravelRoutePayloadPort;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.policy.TravelPlanPolicy;
import com.playdata.calen.travel.dto.*;
import java.math.*;
import java.util.*;
import java.util.function.Function;

/**
 * Frozen DTO assembly from HEAD 4aeb6d7d, before the read projection extraction.
 * Do not replace these methods with calls to the new detail projector: it is the comparison oracle.
 * Route decoding uses the already-extracted route mapper; that boundary did not change in this slice.
 */
final class TravelPlanDetailLegacyOracle {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal ZERO_DISTANCE = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
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
    private final TravelRoutePayloadPort payload;
    private TravelPlanDetailLegacyOracle(TravelRoutePayloadPort payload) { this.payload = payload; }

    static TravelPlanDetailResponse expected(TravelPlan plan, List<TravelBudgetItem> budgets, List<TravelExpenseRecord> records,
            List<TravelExpenseRecord> memories, List<TravelMediaAsset> media, List<TravelRouteSegment> routes,
            TravelRoutePayloadPort payload, Function<Long, String> mediaUrl) {
        return new TravelPlanDetailLegacyOracle(payload).toPlanDetail(plan, budgets, records, memories, media, routes,
                asset -> mediaUrl.apply(asset.getId()));
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
                Boolean.TRUE.equals(plan.getPublicShared()),
                plan.getPublicSharedAt(),
                budgetItems.stream().sorted(BUDGET_ITEM_ORDER).map(this::toBudgetItemResponse).toList(),
                records.stream().sorted(RECORD_ORDER).map(this::toExpenseRecordResponse).toList(),
                memoryRecords.stream().sorted(RECORD_ORDER).map(this::toMemoryRecordResponse).toList(),
                mediaItems.stream().sorted(MEDIA_ORDER).map(item -> toMediaResponse(item, mediaContentUrlResolver.apply(item))).toList(),
                routeSegments.stream().sorted(ROUTE_ORDER).map(this::toRouteSegmentResponse).toList()
        );
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

    private TravelPlanStatus resolvePlanStatus(TravelPlanStatus status) { return TravelPlanPolicy.status(status); }
    private String normalizeColorHex(String color) { return TravelPlanPolicy.color(color); }
    private TravelRouteSegmentResponse toRouteSegmentResponse(TravelRouteSegment route) {
        return TravelRouteMapper.response(TravelRouteMapper.result(route, payload, false));
    }
}
