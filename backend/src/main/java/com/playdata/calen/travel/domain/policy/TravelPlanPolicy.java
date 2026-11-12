package com.playdata.calen.travel.domain.policy;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import com.playdata.calen.travel.domain.model.TravelPlanData.*;

public final class TravelPlanPolicy {
    public static final String DEFAULT_COLOR = "#3182F6";

    private TravelPlanPolicy() {}

    public static Value prepare(Draft draft) {
        if ((draft.startDate() == null) != (draft.endDate() == null))
            throw new BadRequestException("시작일과 종료일을 모두 입력하거나 기간 없음을 선택해 주세요.");
        if (draft.startDate() != null && draft.startDate().isAfter(draft.endDate()))
            throw new BadRequestException("Travel start date cannot be after end date.");
        return new Value(draft.name().trim(), trimToNull(draft.destination()), draft.startDate(), draft.endDate(),
                TravelMoneyPolicy.currency(draft.homeCurrency()), draft.headCount(), status(draft.status()), color(draft.colorHex()), trimToNull(draft.memo()));
    }

    public static TravelPlanStatus status(String value) {
        if (value == null || value.isBlank()) return TravelPlanStatus.PLANNED;
        try { return TravelPlanStatus.valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException exception) { throw new BadRequestException("Unsupported travel status."); }
    }

    public static TravelPlanStatus status(TravelPlanStatus value) { return value == null ? TravelPlanStatus.PLANNED : value; }

    public static String color(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (normalized.isEmpty()) return DEFAULT_COLOR;
        if (!normalized.matches("^#[0-9A-F]{6}$")) throw new BadRequestException("Travel color must use #RRGGBB format.");
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
