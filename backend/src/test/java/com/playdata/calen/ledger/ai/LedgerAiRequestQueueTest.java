package com.playdata.calen.ledger.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.common.exception.TooManyRequestsException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LedgerAiRequestQueueTest {
    private final LedgerAiFeatureConfig config = config("http://localhost:1234");

    @Test
    void saturatedProviderRejectsImmediatelyAndOtherProviderStillWorks() throws Exception {
        LedgerAiRequestProperties properties = new LedgerAiRequestProperties();
        properties.setMaxConcurrent(1);
        properties.setMaxWaiting(0);
        LedgerAiRequestQueue queue = new LedgerAiRequestQueue(properties);
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = blockedRequest(queue, active, release);
        try {
            assertThat(active.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> queue.execute(config, () -> "unexpected"))
                    .isInstanceOf(TooManyRequestsException.class);
            assertThat(queue.execute(config("http://localhost:5678"), () -> "other")).isEqualTo("other");
        } finally {
            release.countDown();
            worker.join(2000);
        }
        assertThat(worker.isAlive()).isFalse();
        assertThat(queue.execute(config, () -> "next")).isEqualTo("next");
    }

    @Test
    void waitingExpiresWithoutCallingModelAndAdmissionIsReleased() throws Exception {
        LedgerAiRequestProperties properties = new LedgerAiRequestProperties();
        properties.setMaxConcurrent(1);
        properties.setMaxWaiting(1);
        properties.setWaitTimeout(Duration.ofMillis(30));
        LedgerAiRequestQueue queue = new LedgerAiRequestQueue(properties);
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = blockedRequest(queue, active, release);
        AtomicBoolean called = new AtomicBoolean();
        try {
            assertThat(active.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> queue.execute(config, () -> { called.set(true); return "unexpected"; }))
                    .isInstanceOf(TooManyRequestsException.class).hasMessageContaining("대기 시간");
            assertThat(called).isFalse();
        } finally {
            release.countDown();
            worker.join(2000);
        }
        assertThat(queue.execute(config, () -> "next")).isEqualTo("next");
    }

    @Test
    void cancelledWaitPreservesInterruptAndReleasesCapacity() throws Exception {
        LedgerAiRequestProperties properties = new LedgerAiRequestProperties();
        properties.setMaxConcurrent(1);
        LedgerAiRequestQueue queue = new LedgerAiRequestQueue(properties);
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch waitingStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = blockedRequest(queue, active, release);
        Thread waiter = new Thread(() -> {
            waitingStarted.countDown();
            try { queue.execute(config, () -> "unexpected"); }
            catch (Throwable exception) {
                failure.set(exception);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertThat(active.await(2, TimeUnit.SECONDS)).isTrue();
            waiter.start();
            assertThat(waitingStarted.await(2, TimeUnit.SECONDS)).isTrue();
            waiter.interrupt();
            waiter.join(2000);
            assertThat(waiter.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(CancellationException.class);
            assertThat(interrupted).isTrue();
        } finally {
            waiter.interrupt();
            release.countDown();
            worker.join(2000);
        }
        assertThat(queue.execute(config, () -> "next")).isEqualTo("next");
    }

    @Test
    void modelFailureDoesNotLeakPermits() {
        LedgerAiRequestQueue queue = new LedgerAiRequestQueue();
        assertThatThrownBy(() -> queue.execute(config, () -> { throw new IllegalStateException("model failed"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(queue.execute(config, () -> "next")).isEqualTo("next");
    }

    @Test
    void configuredSixRequestsEnterUpstreamTogetherWhileTheSeventhIsRejected() throws Exception {
        var properties = new LedgerAiRequestProperties();
        properties.setMaxConcurrent(6);
        properties.setMaxWaiting(0);
        var queue = new LedgerAiRequestQueue(properties);
        var entered = new CountDownLatch(6);
        var release = new CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        var tasks = new java.util.ArrayList<java.util.concurrent.Future<String>>();
        try {
            for (int i = 0; i < 6; i++) tasks.add(pool.submit(() -> queue.execute(config, () -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                }
                return "done";
            })));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> queue.execute(config, () -> "overflow")).isInstanceOf(TooManyRequestsException.class);
            release.countDown();
            for (var task : tasks) assertThat(task.get(5, TimeUnit.SECONDS)).isEqualTo("done");
            assertThat(queue.execute(config, () -> "next")).isEqualTo("next");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void trailingSlashCannotBypassProviderLimit() throws Exception {
        LedgerAiRequestProperties properties = new LedgerAiRequestProperties();
        properties.setMaxConcurrent(1);
        properties.setMaxWaiting(0);
        LedgerAiRequestQueue queue = new LedgerAiRequestQueue(properties);
        CountDownLatch active = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = blockedRequest(queue, active, release);
        try {
            assertThat(active.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> queue.execute(config("http://localhost:1234/"), () -> "unexpected"))
                    .isInstanceOf(TooManyRequestsException.class);
        } finally {
            release.countDown();
            worker.join(2000);
        }
    }

    private Thread blockedRequest(LedgerAiRequestQueue queue, CountDownLatch active, CountDownLatch release) {
        Thread thread = new Thread(() -> queue.execute(config, () -> {
            active.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            }
            return "done";
        }));
        thread.start();
        return thread;
    }

    private static LedgerAiFeatureConfig config(String baseUrl) {
        return new LedgerAiFeatureConfig(LedgerAiProvider.LMSTUDIO, "vision-model", baseUrl,
                "/v1/chat/completions", "/v1/models", "", 0.2, 4096);
    }
}
