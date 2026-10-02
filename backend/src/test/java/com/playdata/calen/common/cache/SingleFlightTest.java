package com.playdata.calen.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SingleFlightTest {
    @Test
    void concurrentMissesShareLoaderWithoutRetainingCompletedValues() throws Exception {
        SingleFlight flights = new SingleFlight();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> flights.execute("key", () -> {
            calls.incrementAndGet(); entered.countDown(); await(release); return "value";
        }));
        entered.await();
        CompletableFuture<String> second = new CompletableFuture<>();
        Thread waiter = new Thread(() -> {
            try { second.complete(flights.execute("key", () -> { calls.incrementAndGet(); return "wrong"; })); }
            catch (Throwable failure) { second.completeExceptionally(failure); }
        });
        waiter.start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (waiter.getState() != Thread.State.TIMED_WAITING) Thread.onSpinWait();
            });
        } finally { release.countDown(); }
        assertThat(first.get()).isEqualTo("value");
        assertThat(second.get()).isEqualTo("value");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(flights.execute("key", () -> "new")).isEqualTo("new");
    }

    @Test
    void invalidatedLoaderCannotPublishOverNewGeneration() throws Exception {
        SingleFlight flights = new SingleFlight();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger published = new AtomicInteger();
        CompletableFuture<String> old = CompletableFuture.supplyAsync(() -> flights.execute("key", () -> {
            entered.countDown(); await(release);
            flights.publishIfCurrent("key", () -> published.set(1));
            return "old";
        }));
        entered.await();
        flights.invalidate("key");
        try {
            assertThat(flights.execute("key", () -> {
                flights.publishIfCurrent("key", () -> published.set(2)); return "new";
            })).isEqualTo("new");
        } finally { release.countDown(); }
        assertThat(old.get()).isEqualTo("old");
        assertThat(published.get()).isEqualTo(2);
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
}
