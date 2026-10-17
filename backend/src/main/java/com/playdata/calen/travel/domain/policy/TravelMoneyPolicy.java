package com.playdata.calen.travel.domain.policy;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.domain.model.TravelBudgetData.*;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class TravelMoneyPolicy {
    private TravelMoneyPolicy() {}

    public static String currency(String value) {
        // Preserve the existing normalization, including the process locale.
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!normalized.matches("[A-Z]{3}")) throw new BadRequestException("Currency code must be a 3-letter ISO 4217 code.");
        return normalized;
    }

    public static BigDecimal rate(String currency, BigDecimal provided) {
        if ("KRW".equals(currency)) return BigDecimal.ONE.setScale(6, RoundingMode.HALF_UP);
        if (provided == null) return null;
        if (provided.signum() <= 0) throw new BadRequestException("Exchange rate must be greater than 0.");
        return provided.setScale(6, RoundingMode.HALF_UP);
    }

    public static BigDecimal toKrw(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    public static Value budget(Draft draft, String currency, BigDecimal rate) {
        String memo = draft.memo() == null || draft.memo().trim().isEmpty() ? null : draft.memo().trim();
        return new Value(draft.category().trim(), draft.title().trim(), draft.amount().setScale(2, RoundingMode.HALF_UP),
                currency, rate, toKrw(draft.amount(), rate), memo, draft.displayOrder() == null ? 0 : draft.displayOrder());
    }
}
