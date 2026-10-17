package com.playdata.calen.travel.adapter.out.cache;

import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.travel.adapter.support.TravelOwnedMapMapper;
import com.playdata.calen.travel.application.port.out.TravelOwnedMapCachePort;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.Overview;
import com.playdata.calen.travel.dto.TravelMyMapOverviewResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OwnedTravelMapCache implements TravelOwnedMapCachePort {
    /** Same JSON envelope as the former private TravelService record; no Java type id is written. */
    public record VersionedMapOverview(long generation, TravelMyMapOverviewResponse overview) {}
    private final RedisCacheService redis;
    private final TravelSummaryCache summaries;
    private final long ttlSeconds;
    public OwnedTravelMapCache(RedisCacheService redis, TravelSummaryCache summaries,
            @Value("${app.travel.summary-cache-ttl-seconds:60}") long ttlSeconds) {
        this.redis = redis; this.summaries = summaries; this.ttlSeconds = ttlSeconds;
    }
    @Override public Overview get(Long owner, Long generation) {
        if (generation == null) return TravelOwnedMapMapper.value(redis.get(TravelSummaryCache.mapKey(owner), TravelMyMapOverviewResponse.class));
        var cached = redis.get(TravelSummaryCache.mapKey(owner), VersionedMapOverview.class);
        return cached != null && cached.generation() == generation ? TravelOwnedMapMapper.value(cached.overview()) : null;
    }
    @Override public void publish(Long owner, Long generation, Overview value) {
        if (ttlSeconds <= 0) return;
        var response = TravelOwnedMapMapper.response(value);
        summaries.publishIfCurrent(TravelSummaryCache.mapKey(owner), generation == null ? response : new VersionedMapOverview(generation, response), Duration.ofSeconds(ttlSeconds));
    }
}
