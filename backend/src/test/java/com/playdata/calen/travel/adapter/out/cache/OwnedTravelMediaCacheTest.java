package com.playdata.calen.travel.adapter.out.cache;

import static org.assertj.core.api.Assertions.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.Download;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OwnedTravelMediaCacheTest {
    final AtomicLong millis=new AtomicLong(1000),nanos=new AtomicLong(1000);
    final OwnedTravelMediaCache cache=new OwnedTravelMediaCache(300,millis::get,nanos::get);
    final Download value=new Download("test/a","image/png","a.png");
    @Test void ownerAndMediaIdsAreBothPartOfTheKey() {
        cache.put(7L,9L,value);assertThat(cache.get(7L,9L)).isSameAs(value);
        assertThat(cache.get(8L,9L)).isNull();assertThat(cache.get(7L,10L)).isNull();
    }
    @Test void zeroAndNegativeTtlDisablePublication() {
        for(long ttl:new long[]{0,-1}) {var disabled=new OwnedTravelMediaCache(ttl,millis::get,nanos::get);disabled.put(7L,9L,value);assertThat(disabled.get(7L,9L)).isNull();}
    }
    @Test void wallClockExpiryStillRemovesEntriesAtTheExactLegacyDeadline() {
        cache.put(7L,9L,value);millis.addAndGet(299999);assertThat(cache.get(7L,9L)).isSameAs(value);
        millis.incrementAndGet();assertThat(cache.get(7L,9L)).isNull();
    }
    @Test void monotonicExpiryAndPeriodicEvictionRemainIndependentOfWallClock() {
        cache.put(7L,9L,value);nanos.addAndGet(300_000_000_000L);cache.evictExpired();assertThat(cache.get(7L,9L)).isNull();
    }
    @Test void invalidateRemovesOnlyTheSpecifiedOwnersMedia() {
        cache.put(7L,9L,value);cache.put(8L,9L,value);cache.invalidate(7L,9L);
        assertThat(cache.get(7L,9L)).isNull();assertThat(cache.get(8L,9L)).isSameAs(value);
    }
    @Test void boundedLruCapacityRemains4096() {
        for(long i=1;i<=4096;i++)cache.put(7L,i,value);
        assertThat(cache.get(7L,1L)).isNotNull();cache.put(7L,4097L,value);
        assertThat(cache.get(7L,1L)).isNotNull();assertThat(cache.get(7L,2L)).isNull();
    }
}
