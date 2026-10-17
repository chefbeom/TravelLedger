package com.playdata.calen.ledger.domain.policy;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.model.LedgerTravelData.*;
import java.math.*;
import java.time.LocalTime;
import java.util.*;

public final class LedgerTravelPolicy {
    private LedgerTravelPolicy() {}
    public static final String GROUP = "여행", DETAIL = "기타", PAYMENT = "여행 미지정";
    public static void forward(Long owner, Travel record) {
        if (!"LEDGER".equals(record.kind())) throw new BadRequestException("Only travel ledger records can be reflected into the household ledger.");
        if (!record.plan().owner().equals(owner)) throw new BadRequestException("Travel record owner does not match the ledger owner.");
    }
    public static void reverse(Plan plan, Entry entry) {
        if (entry.type() != EntryType.EXPENSE) throw new BadRequestException("Only expense ledger entries can be linked to travel records.");
        if (!entry.owner().equals(plan.owner())) throw new BadRequestException("Travel plan owner does not match the ledger owner.");
        if (entry.date().isBefore(plan.start()) || entry.date().isAfter(plan.end())) throw new BadRequestException("Ledger entry date must be within the selected travel period.");
        if (entry.travelPlan() != null && !entry.travelPlan().equals(plan.id())) throw new BadRequestException("Ledger entry is already linked to another travel plan.");
    }
    public static String detailName(String category) { return fallback(category, DETAIL); }
    public static String createdDetailName(String category) { return limit(detailName(category), 80); }
    public static Write forwardWrite(Travel record, Long group, Long detail, Long payment) {
        String currency = currency(record.currency()); boolean foreign = currency != null && !"KRW".equals(currency);
        var parts = new ArrayList<String>(); parts.add("여행: " + record.plan().name());
        part(parts, "장소", record.place()); part(parts, "지역", record.region()); part(parts, "국가", record.country()); part(parts, "메모", record.memo());
        return new Write(record.date(), record.time(), limit("[여행] " + fallback(record.title(), record.category()), 120),
                limit(String.join("\n", parts), 500), record.amountKrw() == null ? BigDecimal.ZERO : record.amountKrw(),
                foreign ? currency : null, foreign ? record.amount() : null, foreign ? record.rate() : null, EntryType.EXPENSE,
                group, detail, payment, record.plan().id(), record.id());
    }
    public static TravelValue travelValue(Entry entry, Boolean shared) {
        String category = entry.detail() != null ? fallback(entry.detail().name(), DETAIL)
                : entry.group() != null ? fallback(entry.group().name(), DETAIL) : DETAIL;
        String currency = currency(entry.currency());
        boolean foreign = currency != null && !"KRW".equals(currency) && entry.foreignAmount() != null && entry.rate() != null;
        BigDecimal amount = (foreign ? entry.foreignAmount() : entry.amount()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal rate = (foreign ? entry.rate() : BigDecimal.ONE).setScale(6, RoundingMode.HALF_UP);
        return new TravelValue(entry.date(), entry.time() == null ? LocalTime.MIDNIGHT : entry.time(), limit(category, 80),
                limit(fallback(entry.title(), category), 120), amount, foreign ? currency : "KRW", rate,
                entry.amount().setScale(2, RoundingMode.HALF_UP), shared == null ? false : shared, limit(text(entry.memo()), 500));
    }
    public static Write reverseWrite(Entry entry, Long plan, Long record) {
        return new Write(entry.date(), entry.time(), entry.title(), entry.memo(), entry.amount(), entry.currency(), entry.foreignAmount(), entry.rate(),
                entry.type(), entry.group().id(), entry.detail() == null ? null : entry.detail().id(), entry.payment().id(), plan, record);
    }
    private static String currency(String value) { var result = text(value); return result == null ? null : result.toUpperCase(Locale.ROOT); }
    private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String fallback(String value, String fallback) { var result = text(value); return result == null ? fallback : result; }
    private static String limit(String value, int max) { var result = value == null ? "" : value; return result.length() <= max ? result : result.substring(0, max); }
    private static void part(List<String> values, String name, String value) { var text = text(value); if (text != null) values.add(name + ": " + text); }
}
