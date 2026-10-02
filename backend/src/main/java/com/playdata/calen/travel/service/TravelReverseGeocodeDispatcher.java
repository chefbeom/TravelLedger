package com.playdata.calen.travel.service;

import com.playdata.calen.common.cache.BoundedExpiringCache;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.TooManyRequestsException;
import com.playdata.calen.travel.dto.TravelReverseGeocodeResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/** Keeps provider throttling off HTTP threads and merges requests for the same coordinate. */
@Service
public class TravelReverseGeocodeDispatcher {
    private final TravelReverseGeocodeService service;
    private final ThreadPoolTaskExecutor executor;
    private final ConcurrentHashMap<String, CompletableFuture<TravelReverseGeocodeResponse>> inFlight = new ConcurrentHashMap<>();
    private final BoundedExpiringCache<String, TravelReverseGeocodeResponse> localCache = new BoundedExpiringCache<>(4096);
    @Value("${app.travel.reverse-geocode-cache-ttl-hours:24}")
    private long cacheTtlHours = 24;

    public TravelReverseGeocodeDispatcher(TravelReverseGeocodeService service,
            @Value("${app.travel.reverse-geocode-queue-capacity:16}") int queueCapacity) {
        if (queueCapacity < 0) throw new IllegalArgumentException("Geocode queue capacity cannot be negative");
        this.service = service;
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("reverse-geocode-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
    }

    public CompletableFuture<TravelReverseGeocodeResponse> reverseGeocode(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new BadRequestException("GPS coordinates are outside the valid range.");
        }
        String key = String.format(Locale.US, "%.5f:%.5f", latitude, longitude);
        TravelReverseGeocodeResponse cached = localCache.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        CompletableFuture<TravelReverseGeocodeResponse> result = new CompletableFuture<>();
        CompletableFuture<TravelReverseGeocodeResponse> existing = inFlight.putIfAbsent(key, result);
        if (existing != null) return existing.thenApply(response -> response);
        try {
            executor.execute(() -> {
                try {
                    TravelReverseGeocodeResponse response = localCache.get(key);
                    if (response == null) response = service.reverseGeocode(latitude, longitude);
                    if (response != null && !response.isEmpty()) {
                        localCache.put(key, response, Duration.ofHours(Math.max(1, cacheTtlHours)));
                    }
                    result.complete(response == null ? TravelReverseGeocodeResponse.empty() : response);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    inFlight.remove(key, result);
                }
            });
        } catch (org.springframework.core.task.TaskRejectedException exception) {
            inFlight.remove(key, result);
            result.completeExceptionally(new TooManyRequestsException("주소 조회 요청이 많습니다. 잠시 후 다시 시도해 주세요.", 5));
        }
        // Each HTTP request gets its own dependent future so cancelling one caller
        // cannot cancel a provider job that other callers are waiting for.
        return result.thenApply(response -> response);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    void evictExpiredAddresses() {
        localCache.evictExpired();
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdown();
        inFlight.values().forEach(future -> future.completeExceptionally(new IllegalStateException("Geocode dispatcher stopped")));
        inFlight.clear();
    }
}
