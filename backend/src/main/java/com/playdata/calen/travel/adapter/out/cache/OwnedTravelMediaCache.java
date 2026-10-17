package com.playdata.calen.travel.adapter.out.cache;

import com.playdata.calen.common.cache.BoundedExpiringCache;
import com.playdata.calen.travel.application.port.out.OwnedTravelMediaCachePort;
import com.playdata.calen.travel.domain.model.TravelMediaData.Download;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Same local owner/id metadata cache and immediate invalidation; not a content or authorization cache for public shares. */
@Component
public class OwnedTravelMediaCache implements OwnedTravelMediaCachePort {
    private record Key(Long userId, Long mediaId) {}
    private record Entry(Download download, long expiresAtEpochMilli) {
        boolean expired(long now) {
            return expiresAtEpochMilli > 0L && now >= expiresAtEpochMilli;
        }
    }
    private final BoundedExpiringCache<Key, Entry> entries;
    private final long ttlSeconds;
    private final LongSupplier millis;
    @Autowired
    public OwnedTravelMediaCache(@Value("${app.travel.media-download-cache-ttl-seconds:300}") long ttlSeconds) {
        this(ttlSeconds, System::currentTimeMillis, System::nanoTime);
    }

    OwnedTravelMediaCache(long ttlSeconds, LongSupplier millis, LongSupplier nanos) {
        this.ttlSeconds = ttlSeconds;
        this.millis = millis;
        this.entries = new BoundedExpiringCache<>(4096, nanos);
    }

    @Override
    public Download get(Long owner, Long id) {
        var key = new Key(owner, id);
        var entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expired(millis.getAsLong())) {
            entries.remove(key, entry);
            return null;
        }
        return entry.download();
    }

    @Override
    public void put(Long owner, Long id, Download download) {
        Duration ttl = ttlSeconds <= 0 ? Duration.ZERO : Duration.ofSeconds(ttlSeconds);
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        long expires = millis.getAsLong() + ttl.toMillis();
        entries.put(new Key(owner, id), new Entry(download, expires), ttl);
    }

    @Override
    public void invalidate(Long owner, Long id) {
        entries.remove(new Key(owner, id));
    }

    @Scheduled(fixedDelay = 60000)
    public void evictExpired() {
        entries.evictExpired();
    }
}
