package com.playdata.calen.travel.domain.policy;

import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.domain.model.TravelReferenceData.*;
import com.playdata.calen.travel.domain.model.TravelReferenceData.Record;
import java.util.*;
import java.util.stream.Stream;

public final class TravelReferencePolicy {
    private TravelReferencePolicy() {}
    private static final List<String> BUDGETS = List.of("Flight", "Stay", "Transport", "Food", "Shopping", "Ticket", "Insurance", "Etc");
    private static final List<String> MEMORIES = List.of("Spot", "Cafe", "View", "Meal", "Hotel", "Transit", "Shopping");
    public static Catalog catalog(Source s) {
        return new Catalog(List.of("PLANNED", "COMPLETED", "SAMPLE"), sorted(BUDGETS.stream(), s.budgetCategories().stream()),
                sorted(BUDGETS.stream(), s.records().stream().filter(r -> TravelRecordPolicy.matches(r.type(), TravelRecordType.LEDGER)).map(Record::category)),
                sorted(MEMORIES.stream(), s.records().stream().filter(r -> TravelRecordPolicy.matches(r.type(), TravelRecordType.MEMORY)).map(Record::category)),
                sorted(s.records().stream().map(Record::country)), sorted(s.records().stream().map(Record::region)), sorted(s.records().stream().map(Record::place)));
    }
    public static Set<String> currencies(String input) {
        var result = new LinkedHashSet<String>();
        if (input == null || input.isBlank()) result.addAll(List.of("KRW", "USD", "JPY", "CNY", "EUR"));
        else { for (String currency : input.split(",")) result.add(TravelMoneyPolicy.currency(currency)); result.add("KRW"); }
        return result;
    }
    @SafeVarargs private static List<String> sorted(Stream<String>... streams) {
        var result = new LinkedHashSet<String>();
        // Sort each source independently, then retain case-sensitive first occurrence, as before.
        for (var stream : streams) stream.map(v -> v == null ? null : v.trim()).filter(v -> v != null && !v.isEmpty())
                .sorted(String::compareToIgnoreCase).forEach(result::add);
        return List.copyOf(result);
    }
}
