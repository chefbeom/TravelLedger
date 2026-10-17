package com.playdata.calen.travel.domain.policy;

import com.playdata.calen.travel.domain.model.TravelOwnedPlansData.*;
import com.playdata.calen.travel.domain.model.TravelPlanData.Metrics;
import java.math.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class TravelOwnedPlansPolicy {
    private TravelOwnedPlansPolicy() {}
    public static Head head(Head p) {
        return new Head(p.id(), p.name(), p.destination(), p.startDate(), p.endDate(), p.currency(), p.headCount(),
                p.status() == null ? "PLANNED" : p.status(), TravelPlanPolicy.color(p.color()), p.memo());
    }
    public static Summary aggregate(Aggregate a) {
        return new Summary(head(a.head()), new Metrics(money(a.planned(), 2), money(a.actual(), 2), count(a.budgets()), count(a.records()),
                count(a.memories()), count(a.media()), count(a.routes()), money(a.distance(), 3), count(a.duration()), count(a.steps())));
    }
    public static List<Summary> summaries(Rows rows) {
        var budgets = group(rows.budgets(), Budget::plan);
        var records = group(rows.records(), v -> v.plan().id());
        var memories = group(rows.memories(), v -> v.plan().id());
        var pictures = group(rows.media(), v -> v.planId());
        var routes = group(rows.routes(), v -> v.plan().id());
        // Preserve the old linear grouping cost rather than scanning the entire portfolio once per plan.
        return rows.plans().stream().map(p -> new Summary(head(p), metrics(new Rows(List.of(), budgets.getOrDefault(p.id(), List.of()),
                records.getOrDefault(p.id(), List.of()), memories.getOrDefault(p.id(), List.of()), pictures.getOrDefault(p.id(), List.of()), routes.getOrDefault(p.id(), List.of()))))).toList();
    }
    private static <T> Map<Long, List<T>> group(List<T> values, Function<T, Long> key) { return values.stream().collect(Collectors.groupingBy(key)); }
    public static Metrics metrics(Rows rows) {
        var b = rows.budgets(); var r = rows.records(); var m = rows.memories(); var p = rows.media(); var paths = rows.routes();
        return new Metrics(sum(b, v -> v.value().value().amountKrw(), 2), sum(r, v -> v.value().amountKrw(), 2), b.size(), r.size(), m.size(), p.size(), paths.size(),
                sum(paths, v -> v.value().distanceKm(), 3), integerSum(paths, v -> v.value().durationMinutes()), integerSum(paths, v -> v.value().stepCount()));
    }
    private static BigDecimal money(BigDecimal value, int scale) { return (value == null ? BigDecimal.ZERO : value).setScale(scale, RoundingMode.HALF_UP); }
    private static int count(Long value) { return value == null || value <= 0 ? 0 : value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value.intValue(); }
    private static <T> BigDecimal sum(Collection<T> values, Function<T, BigDecimal> amount, int scale) {
        return values.stream().map(amount).filter(Objects::nonNull).reduce(BigDecimal.ZERO.setScale(scale), BigDecimal::add).setScale(scale, RoundingMode.HALF_UP);
    }
    private static <T> int integerSum(Collection<T> values, Function<T, Integer> amount) { return values.stream().map(amount).filter(Objects::nonNull).reduce(0, Integer::sum); }
}
