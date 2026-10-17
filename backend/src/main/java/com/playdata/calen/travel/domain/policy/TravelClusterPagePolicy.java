package com.playdata.calen.travel.domain.policy;

import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Identical ordering for owned and shared clusters without depending on HTTP/media URL DTOs. */
public final class TravelClusterPagePolicy {
    public static final int DEFAULT_SIZE = 12;
    public static final int MAX_SIZE = 36;
    private TravelClusterPagePolicy() {}
    public static int page(Integer page) { return Math.max(page == null ? 0 : page, 0); }
    public static int size(Integer size) { return Math.min(Math.max(size == null ? DEFAULT_SIZE : size, 1), MAX_SIZE); }
    public record Slice<T>(List<T> photos, int page, int size, int total, boolean hasNext) {}

    public static <T> Slice<T> slice(List<T> photos, Long representative, Long focus, Integer page, Integer size,
            Function<T, Long> id, Function<T, LocalDate> date, Function<T, LocalTime> time,
            Function<T, LocalDateTime> uploaded) {
        int p = page(page), s = size(size);
        Long first = p == 0 ? focus : null;
        List<T> values = photos == null ? List.of() : photos;
        Comparator<T> order = Comparator.comparingInt((T photo) -> matches(photo, first, id) ? 0 : 1)
                .thenComparingInt(photo -> matches(photo, representative, id) ? 0 : 1)
                .thenComparing(date, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(time, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(uploaded, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(id, Comparator.nullsLast(Comparator.reverseOrder()));
        var sorted = values.stream().sorted(order).toList();
        int total = sorted.size();
        // Preserve existing in-memory paging arithmetic and error behavior.
        int from = Math.min(p * s, total), to = Math.min(from + s, total);
        return new Slice<>(sorted.subList(from, to), p, s, total, to < total);
    }
    private static <T> boolean matches(T photo, Long target, Function<T, Long> id) {
        return target != null && photo != null && id.apply(photo) != null && id.apply(photo).equals(target);
    }
}
