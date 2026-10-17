package com.playdata.calen.travel.adapter.out.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.travel.adapter.support.TravelOwnedPlansMapper;
import com.playdata.calen.travel.application.port.out.TravelOwnedPlansCachePort;
import com.playdata.calen.travel.domain.model.TravelOwnedPlansData.*;
import com.playdata.calen.travel.dto.*;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OwnedTravelPlansCache implements TravelOwnedPlansCachePort {
    private static final TypeReference<List<TravelPlanSummaryResponse>> PLANS = new TypeReference<>() {};
    private final RedisCacheService redis;
    private final TravelSummaryCache summaries;
    private final long ttl;
    public OwnedTravelPlansCache(RedisCacheService redis, TravelSummaryCache summaries, @Value("${app.travel.summary-cache-ttl-seconds:60}") long ttl) {
        this.redis = redis; this.summaries = summaries; this.ttl = ttl;
    }
    @Override public List<Summary> plans(Long owner) {
        var cached = redis.get(TravelSummaryCache.plansKey(owner), PLANS);
        return cached == null ? null : cached.stream().map(TravelOwnedPlansMapper::summary).toList();
    }
    @Override public Portfolio portfolio(Long owner) { return TravelOwnedPlansMapper.portfolio(redis.get(TravelSummaryCache.portfolioKey(owner), TravelPortfolioResponse.class)); }
    @Override public void publishPlans(Long owner, List<Summary> value) {
        if (ttl > 0) summaries.publishIfCurrent(TravelSummaryCache.plansKey(owner), value.stream().map(TravelOwnedPlansMapper::summary).toList(), Duration.ofSeconds(ttl));
    }
    @Override public void publishPortfolio(Long owner, Portfolio value) {
        if (ttl > 0) summaries.publishIfCurrent(TravelSummaryCache.portfolioKey(owner), TravelOwnedPlansMapper.portfolio(value), Duration.ofSeconds(ttl));
    }
}
