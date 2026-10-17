package com.playdata.calen.travel.domain.model;

import com.playdata.calen.travel.domain.TravelRecordType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public final class TravelRecordData {
    private TravelRecordData() {}

    public record ExpenseDraft(LocalDate date, LocalTime time, String category, String title, BigDecimal amount,
            String currency, BigDecimal rate, String country, String region, String placeName,
            BigDecimal latitude, BigDecimal longitude, String memo) {}
    public record MemoryDraft(LocalDate date, LocalTime time, String category, String title, String country,
            String region, String placeName, BigDecimal latitude, BigDecimal longitude, Boolean shared, String memo) {}
    public record Value(TravelRecordType type, LocalDate date, LocalTime time, String category, String title,
            BigDecimal amount, String currency, BigDecimal rate, BigDecimal amountKrw, String country,
            String region, String placeName, BigDecimal latitude, BigDecimal longitude, boolean shared, String memo) {}
    public record Plan(Long id, String name, String colorHex) {}
    public record Access(Long id, Plan plan, TravelRecordType type) {}
    public record Result(Long id, Plan plan, Value value) {}
}
