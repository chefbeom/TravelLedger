package com.playdata.calen.travel.adapter.out.cache;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.cache.RedisCacheService;
import com.playdata.calen.travel.adapter.out.cache.OwnedTravelMapCache.VersionedMapOverview;
import com.playdata.calen.travel.adapter.support.TravelOwnedMapMapper;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.Overview;
import com.playdata.calen.travel.dto.TravelMyMapOverviewResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class OwnedTravelMapCacheTest {
    final RedisCacheService redis = mock(RedisCacheService.class);
    final TravelSummaryCache summaries = new TravelSummaryCache(redis);
    final OwnedTravelMapCache cache = new OwnedTravelMapCache(redis, summaries, 60);
    final String key = "travel:mymap:overview:7";
    final Overview value = new Overview(1, 0, 0, 0, 0, new BigDecimal("0.000"), List.of(), List.of(), List.of(), List.of());

    @Test void exactGenerationEnvelopeUsesTheExistingKeyAndTtl() {
        var dto = TravelOwnedMapMapper.response(value);
        when(redis.get(key, VersionedMapOverview.class)).thenReturn(new VersionedMapOverview(12, dto));
        assertThat(cache.get(7L, 11L)).isNull(); assertThat(cache.get(7L, 12L)).isEqualTo(value);
        summaries.execute(key, () -> { cache.publish(7L, 12L, value); return value; });
        verify(redis).set(key, new VersionedMapOverview(12, dto), Duration.ofSeconds(60));
    }

    @Test void disabledWritesStillReadLegacyPlainCacheAndKeepNullFields() {
        var dto = new TravelMyMapOverviewResponse(2, 0, 0, 0, 0, null, null, null, null, null);
        when(redis.get(key, TravelMyMapOverviewResponse.class)).thenReturn(dto);
        var disabled = new OwnedTravelMapCache(redis, summaries, 0);
        assertThat(TravelOwnedMapMapper.response(disabled.get(7L, null))).isEqualTo(dto);
        summaries.execute(key, () -> { disabled.publish(7L, null, value); return value; });
        verify(redis, never()).set(anyString(), any(), any());
    }

    @Test void missingEnvelopeOrMissingPayloadIsAMissRatherThanAnEmptyReplacement() {
        assertThat(cache.get(7L, 11L)).isNull();
        when(redis.get(key, VersionedMapOverview.class)).thenReturn(new VersionedMapOverview(11, null));
        assertThat(cache.get(7L, 11L)).isNull(); verify(redis, never()).set(anyString(), any(), any());
    }

    @Test void writesOutsideTheSharedFlightRemainForbidden() {
        cache.publish(7L, null, value); verifyNoInteractions(redis);
        summaries.execute(key, () -> { cache.publish(7L, null, value); return value; });
        verify(redis).set(key, TravelOwnedMapMapper.response(value), Duration.ofSeconds(60));
    }

    @Test void legacyInvalidationCancelsTheNewReadersInFlightPublication() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var old = executor.submit(() -> summaries.execute(key, () -> {
                started.countDown(); await(release); cache.publish(7L, 12L, value); return value;
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            summaries.invalidateOwner(7L);
            summaries.execute(key, () -> { cache.publish(7L, 13L, value); return value; });
            release.countDown(); assertThat(old.get(5, TimeUnit.SECONDS)).isEqualTo(value);
            verify(redis, never()).set(key, new VersionedMapOverview(12, TravelOwnedMapMapper.response(value)), Duration.ofSeconds(60));
            verify(redis).set(key, new VersionedMapOverview(13, TravelOwnedMapMapper.response(value)), Duration.ofSeconds(60));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("test barrier timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
