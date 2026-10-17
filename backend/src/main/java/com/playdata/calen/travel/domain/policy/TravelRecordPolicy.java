package com.playdata.calen.travel.domain.policy;

import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.domain.model.TravelRecordData.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;

public final class TravelRecordPolicy {
    private TravelRecordPolicy() {}

    public static boolean matches(TravelRecordType actual, TravelRecordType expected) {
        return expected == TravelRecordType.LEDGER ? actual == null || actual == TravelRecordType.LEDGER : actual == TravelRecordType.MEMORY;
    }

    public static NotFoundException unavailable(TravelRecordType expected) {
        return new NotFoundException(expected == TravelRecordType.LEDGER ? "Travel ledger record not found." : "Travel memory not found.");
    }

    public static Value expense(ExpenseDraft draft, String currency, BigDecimal rate) {
        TravelCoordinatePolicy.requirePair(draft.latitude(), draft.longitude());
        return new Value(TravelRecordType.LEDGER, draft.date(), draft.time() == null ? LocalTime.MIDNIGHT : draft.time(),
                draft.category().trim(), draft.title().trim(), draft.amount().setScale(2, RoundingMode.HALF_UP), currency, rate,
                TravelMoneyPolicy.toKrw(draft.amount(), rate), trim(draft.country()), trim(draft.region()), trim(draft.placeName()),
                TravelCoordinatePolicy.normalize(draft.latitude(), true), TravelCoordinatePolicy.normalize(draft.longitude(), false), false, trim(draft.memo()));
    }

    public static Value memory(MemoryDraft draft) {
        TravelCoordinatePolicy.requirePair(draft.latitude(), draft.longitude());
        return new Value(TravelRecordType.MEMORY, draft.date(), draft.time(), draft.category().trim(), draft.title().trim(),
                new BigDecimal("0.00"), "KRW", new BigDecimal("1.000000"), new BigDecimal("0.00"), trim(draft.country()), trim(draft.region()), trim(draft.placeName()),
                TravelCoordinatePolicy.normalize(draft.latitude(), true), TravelCoordinatePolicy.normalize(draft.longitude(), false),
                Boolean.TRUE.equals(draft.shared()), trim(draft.memo()));
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
