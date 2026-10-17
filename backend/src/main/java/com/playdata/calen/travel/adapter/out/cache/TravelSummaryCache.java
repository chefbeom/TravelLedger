package com.playdata.calen.travel.adapter.out.cache;

import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.common.cache.SingleFlight;
import com.playdata.calen.travel.application.port.out.TravelSummaryInvalidationPort;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One flight registry for legacy readers and migrated writers, with the existing invalidation timing. */
@Component
@RequiredArgsConstructor
public class TravelSummaryCache implements TravelSummaryInvalidationPort {
    public static final String PUBLIC_TRIPS_KEY = "travel:public-trips:overview";
    private final RedisCacheService redis;
    private final SingleFlight flights = new SingleFlight();

    public static String plansKey(Long owner) { return "travel:plans:" + owner; }
    public static String portfolioKey(Long owner) { return "travel:portfolio:" + owner; }
    public static String mapKey(Long owner) { return "travel:mymap:overview:" + owner; }

    public <T> T execute(String key, Supplier<T> loader) { return flights.execute(key, loader); }

    public void publishIfCurrent(String key, Object response, Duration ttl) {
        flights.publishIfCurrent(key, () -> redis.set(key, response, ttl));
    }

    @Override public void invalidateOwner(Long owner) {
        String[] keys = { plansKey(owner), portfolioKey(owner), mapKey(owner), PUBLIC_TRIPS_KEY };
        invalidate(keys);
        afterCommit(() -> invalidate(keys));
    }

    @Override public void invalidatePublic() {
        redis.delete(PUBLIC_TRIPS_KEY);
        afterCommit(() -> redis.delete(PUBLIC_TRIPS_KEY));
    }

    private void invalidate(String[] keys) {
        for (String key : keys) flights.invalidate(key);
        redis.delete(keys);
    }

    private void afterCommit(Runnable callback) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { callback.run(); }
            });
        }
    }
}
