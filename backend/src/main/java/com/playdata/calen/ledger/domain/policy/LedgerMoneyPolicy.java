package com.playdata.calen.ledger.domain.policy;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.model.LedgerPreparationData.Exchange;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;

public final class LedgerMoneyPolicy {
    private LedgerMoneyPolicy() {}
    public static String normalizeCurrency(String code) {
        if (code == null || code.isBlank()) return null;
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) throw new BadRequestException("Currency code must be a 3-letter ISO code.");
        return normalized;
    }
    public static boolean foreign(String currency) { return currency != null && !"KRW".equals(currency); }
    public static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    public static void requireForeignAmount(BigDecimal amount) {
        if (!positive(amount)) throw new BadRequestException("Foreign amount must be greater than 0.");
    }
    public static Exchange calculate(String currency, BigDecimal foreignAmount, BigDecimal rate, LocalDate date, String provider) {
        BigDecimal normalizedRate = rate.setScale(6, RoundingMode.HALF_UP);
        BigDecimal normalizedAmount = foreignAmount.setScale(4, RoundingMode.HALF_UP);
        return new Exchange(normalizedAmount.multiply(normalizedRate).setScale(0, RoundingMode.HALF_UP),
                currency, normalizedAmount, normalizedRate, date, provider);
    }
}
