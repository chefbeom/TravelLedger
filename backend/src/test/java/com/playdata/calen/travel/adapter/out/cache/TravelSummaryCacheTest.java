package com.playdata.calen.travel.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.cache.RedisCacheService;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.transaction.support.TransactionSynchronization;

class TravelSummaryCacheTest {
    private final RedisCacheService redis = mock(RedisCacheService.class);
    private final TravelSummaryCache cache = new TravelSummaryCache(redis);
    private final Duration ttl = Duration.ofSeconds(60);

    @Test void ownerKeysAreInvalidatedImmediatelyAndAgainAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.invalidateOwner(7L);
            verify(redis).delete("travel:plans:7", "travel:portfolio:7", "travel:mymap:overview:7", "travel:public-trips:overview");
            TransactionSynchronizationUtils.triggerAfterCommit();
            verify(redis, times(2)).delete("travel:plans:7", "travel:portfolio:7", "travel:mymap:overview:7", "travel:public-trips:overview");
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void rollbackRetainsImmediateInvalidationWithoutCommitCallback() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.invalidateOwner(7L);
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(redis).delete("travel:plans:7", "travel:portfolio:7", "travel:mymap:overview:7", "travel:public-trips:overview");
            verifyNoMoreInteractions(redis);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void publicOnlyInvalidationKeepsTheExistingKeyAndCommitTiming() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.invalidatePublic();
            verify(redis).delete("travel:public-trips:overview");
            TransactionSynchronizationUtils.triggerAfterCommit();
            verify(redis, times(2)).delete("travel:public-trips:overview");
            verifyNoMoreInteractions(redis);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void invalidatedLoaderCannotPublishAfterAnotherFlightHasPublished() throws Exception {
        String key = TravelSummaryCache.plansKey(7L);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> old = executor.submit(() -> cache.execute(key, () -> {
                started.countDown(); await(release); cache.publishIfCurrent(key, "old", ttl); return "old";
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            cache.invalidateOwner(7L);
            assertThat(cache.execute(key, () -> { cache.publishIfCurrent(key, "new", ttl); return "new"; })).isEqualTo("new");
            release.countDown();
            assertThat(old.get(5, TimeUnit.SECONDS)).isEqualTo("old");
            verify(redis, never()).set(key, "old", ttl);
            verify(redis).set(key, "new", ttl);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void commitInvalidationAlsoCancelsLoaderStartedInThePreCommitWindow() throws Exception {
        String key = TravelSummaryCache.mapKey(7L);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.invalidateOwner(7L);
            Future<String> between = executor.submit(() -> cache.execute(key, () -> {
                started.countDown(); await(release); cache.publishIfCurrent(key, "before commit", ttl); return "before commit";
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            TransactionSynchronizationUtils.triggerAfterCommit();
            cache.execute(key, () -> { cache.publishIfCurrent(key, "committed", ttl); return "committed"; });
            release.countDown(); between.get(5, TimeUnit.SECONDS);
            verify(redis, never()).set(key, "before commit", ttl);
            verify(redis).set(key, "committed", ttl);
        } finally { release.countDown(); executor.shutdownNow(); TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void invalidatingAnotherOwnerDoesNotCancelCurrentOwnersFlight() {
        String key = TravelSummaryCache.portfolioKey(7L);
        cache.execute(key, () -> {
            cache.invalidateOwner(8L);
            cache.publishIfCurrent(key, "own result", ttl);
            return "own result";
        });
        verify(redis).set(key, "own result", ttl);
    }

    @Test void publicationOutsideAFlightDoesNotWriteCache() {
        cache.publishIfCurrent(TravelSummaryCache.plansKey(7L), "untracked", ttl);
        verifyNoInteractions(redis);
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test latch"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
