package com.playdata.calen.travel.domain.model;

import com.playdata.calen.travel.domain.TravelRecordType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class TravelReferenceData {
    private TravelReferenceData() {}
    public record Record(TravelRecordType type, String category, String country, String region, String place) {}
    public record Source(List<String> budgetCategories, List<Record> records) {}
    public record Catalog(List<String> statuses, List<String> budgets, List<String> expenses, List<String> memories,
            List<String> countries, List<String> regions, List<String> places) {}
    public record Quote(String currency, BigDecimal rate, LocalDate date, boolean available, String provider) {}
}
