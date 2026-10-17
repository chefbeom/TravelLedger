package com.playdata.calen.ledger.domain.model;

import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public final class LedgerPreparationData {
    private LedgerPreparationData() {}
    public record Draft(LocalDate entryDate, LocalTime entryTime, String title, String memo, BigDecimal amount,
            String foreignCurrencyCode, BigDecimal foreignAmount, BigDecimal exchangeRateToKrw, EntryType entryType,
            Long categoryGroupId, Long categoryDetailId, Long paymentMethodId, Long travelPlanId, Long travelRecordId) {
        public Draft withoutTravel() { return new Draft(entryDate, entryTime, title, memo, amount, foreignCurrencyCode,
                foreignAmount, exchangeRateToKrw, entryType, categoryGroupId, categoryDetailId, paymentMethodId, null, null); }
    }
    public record Category(Long id, EntryType type) {}
    public record Detail(Long id, Long groupId) {}
    public record Payment(Long id, String name, PaymentMethodKind kind) {}
    public record TravelPeriod(LocalDate from, LocalDate to) {}
    public record Rate(BigDecimal amount, LocalDate date, String provider) {}
    public record Exchange(BigDecimal krwAmount, String currencyCode, BigDecimal foreignAmount,
            BigDecimal rateToKrw, LocalDate rateDate, String provider) {}
    public record Prepared(Draft draft, String title, String memo, Exchange exchange, Long groupId, Long detailId, Payment payment) {}
}
