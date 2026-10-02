package com.playdata.calen.common.cache;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** A small local metadata cache with access-order eviction and monotonic expiry. */
public final class BoundedExpiringCache<K, V> {
    private final int maxEntries;
    private final LongSupplier clock;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);

    public BoundedExpiringCache(int maxEntries) {
        this(maxEntries, System::nanoTime);
    }

    public BoundedExpiringCache(int maxEntries, LongSupplier clock) {
        if (maxEntries < 1) throw new IllegalArgumentException("Cache capacity must be positive");
        this.maxEntries = maxEntries;
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) return null;
        if (clock.getAsLong() - entry.expiresAt() >= 0L) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    public synchronized void put(K key, V value, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || value == null) {
            entries.remove(key);
            return;
        }
        entries.put(key, new Entry<>(value, clock.getAsLong() + ttl.toNanos()));
        while (entries.size() > maxEntries) entries.remove(entries.keySet().iterator().next());
    }

    public synchronized void remove(K key) {
        entries.remove(key);
    }

    public synchronized boolean remove(K key, V value) {
        Entry<V> entry = entries.get(key);
        if (entry == null || !Objects.equals(entry.value(), value)) return false;
        entries.remove(key);
        return true;
    }

    public synchronized void evictExpired() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().expiresAt() >= 0L) iterator.remove();
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    private record Entry<V>(V value, long expiresAt) {}
}
