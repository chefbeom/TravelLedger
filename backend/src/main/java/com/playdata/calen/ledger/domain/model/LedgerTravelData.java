package com.playdata.calen.ledger.domain.model;

import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import java.math.BigDecimal;
import java.time.*;

/** Cross-context facts: no Travel entities/enums, ledger entities or transport DTOs enter the core. */
public final class LedgerTravelData {
    private LedgerTravelData() {}
    public record Plan(Long id, Long owner, String name, LocalDate start, LocalDate end) {}
    public record Travel(Long id, Plan plan, String kind, LocalDate date, LocalTime time, String category, String title,
            BigDecimal amount, String currency, BigDecimal rate, BigDecimal amountKrw, String country, String region, String place, String memo) {}
    public record Named(Long id, String name) {}
    public record Ref(Long id) {}
    public record Entry(Long id, Long owner, EntryType type, LocalDate date, LocalTime time, String title, String memo,
            BigDecimal amount, String currency, BigDecimal foreignAmount, BigDecimal rate, Named group, Named detail,
            Ref payment, Long travelPlan, Long travelRecord) {}
    public record LinkedTravel(Long id, Long plan, Boolean shared) {}
    public record TravelValue(LocalDate date, LocalTime time, String category, String title, BigDecimal amount,
            String currency, BigDecimal rate, BigDecimal amountKrw, Boolean shared, String memo) {}
    public record Write(LocalDate date, LocalTime time, String title, String memo, BigDecimal amount, String currency,
            BigDecimal foreignAmount, BigDecimal rate, EntryType type, Long group, Long detail, Long payment, Long plan, Long record) {}
    public record LedgerResult(Long id, LocalDate date, LocalTime time, String title, String memo, BigDecimal amount,
            String currency, BigDecimal foreignAmount, BigDecimal rate, LocalDate rateDate, String rateProvider, EntryType type,
            Long group, String groupName, Long detail, String detailName, Long payment, String paymentName, PaymentMethodKind paymentKind, Long plan, Long record) {}
    public record TravelResult(Long id, Long plan, String planName, String color, String kind, LocalDate date, LocalTime time,
            String category, String title, BigDecimal amount, String currency, BigDecimal rate, BigDecimal amountKrw,
            String country, String region, String place, BigDecimal latitude, BigDecimal longitude, String memo) {}
}
