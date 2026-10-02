package com.playdata.calen.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BoundedExpiringCacheTest {
    @Test
    void boundsSizeAndEvictsLeastRecentlyUsedEntry() {
        BoundedExpiringCache<String, String> cache = new BoundedExpiringCache<>(2);
        cache.put("a", "A", Duration.ofMinutes(1));
        cache.put("b", "B", Duration.ofMinutes(1));
        assertThat(cache.get("a")).isEqualTo("A");
        cache.put("c", "C", Duration.ofMinutes(1));
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("a")).isEqualTo("A");
    }

    @Test
    void expiresWithoutDependingOnWallClockOrReadingEveryKey() {
        AtomicLong clock = new AtomicLong();
        BoundedExpiringCache<String, String> cache = new BoundedExpiringCache<>(10, clock::get);
        cache.put("old", "old", Duration.ofNanos(10));
        cache.put("new", "new", Duration.ofNanos(20));
        clock.set(10);
        cache.evictExpired();
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("old")).isNull();
        assertThat(cache.get("new")).isEqualTo("new");
        clock.set(20);
        assertThat(cache.get("new")).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    void staleRemovalCannotDeleteRefreshedValue() {
        BoundedExpiringCache<String, String> cache = new BoundedExpiringCache<>(2);
        cache.put("key", "current", Duration.ofSeconds(1));
        assertThat(cache.remove("key", "stale")).isFalse();
        assertThat(cache.get("key")).isEqualTo("current");
        cache.put("key", "disabled", Duration.ZERO);
        assertThat(cache.get("key")).isNull();
    }
}
