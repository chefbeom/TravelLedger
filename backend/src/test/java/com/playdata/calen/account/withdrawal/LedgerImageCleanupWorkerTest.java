package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LedgerImageCleanupWorkerTest {
    @Test void workerRequiresBothExplicitInternalFlags() {
        for (String[] properties : new String[][] {
                {}, {"app.account-withdrawal.internal-workflow-enabled=true"},
                {"app.account-withdrawal.image-cleanup-worker-enabled=true"}}) {
            new ApplicationContextRunner().withUserConfiguration(LedgerImageCleanupWorker.class)
                    .withBean(LedgerImageCleanupExecutor.class, () -> mock(LedgerImageCleanupExecutor.class))
                    .withPropertyValues(properties)
                    .run(context -> assertThat(context).doesNotHaveBean(LedgerImageCleanupWorker.class));
        }
        new ApplicationContextRunner().withUserConfiguration(LedgerImageCleanupWorker.class)
                .withBean(LedgerImageCleanupExecutor.class, () -> mock(LedgerImageCleanupExecutor.class))
                .withPropertyValues("app.account-withdrawal.internal-workflow-enabled=true",
                        "app.account-withdrawal.image-cleanup-worker-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(LedgerImageCleanupWorker.class));
    }

    @Test void batchProcessesAtMostFiveAndStopsWhenNoTaskRemains() {
        var executor = mock(LedgerImageCleanupExecutor.class);
        when(executor.processNext()).thenReturn(LedgerImageCleanupExecutor.Result.SUCCEEDED);
        var worker = new LedgerImageCleanupWorker(executor);
        try { worker.runBatch(); verify(executor, times(5)).processNext(); }
        finally { worker.stop(); }

        var empty = mock(LedgerImageCleanupExecutor.class);
        when(empty.processNext()).thenReturn(LedgerImageCleanupExecutor.Result.NO_TASK);
        var emptyWorker = new LedgerImageCleanupWorker(empty);
        try { emptyWorker.runBatch(); verify(empty, times(1)).processNext(); }
        finally { emptyWorker.stop(); }
    }

    @Test void configurationOrLeaseFailureStopsTheBatch() {
        for (var stop : new LedgerImageCleanupExecutor.Result[] {
                LedgerImageCleanupExecutor.Result.CONFIGURATION_REQUIRED, LedgerImageCleanupExecutor.Result.LOST_LEASE}) {
            var executor = mock(LedgerImageCleanupExecutor.class);
            when(executor.processNext()).thenReturn(stop);
            var worker = new LedgerImageCleanupWorker(executor);
            try { worker.runBatch(); verify(executor, times(1)).processNext(); }
            finally { worker.stop(); }
        }
    }

    @Test void aFailedCycleCanRunAgainWithoutExposingProviderDetails() {
        var executor = mock(LedgerImageCleanupExecutor.class);
        when(executor.processNext()).thenThrow(new IllegalStateException("private provider detail"))
                .thenReturn(LedgerImageCleanupExecutor.Result.NO_TASK);
        var worker = new LedgerImageCleanupWorker(executor);
        try { worker.runBatch(); worker.runBatch(); verify(executor, times(2)).processNext(); }
        finally { worker.stop(); }
    }

    @Test void overlappingCyclesDoNotRunTheSameExecutorConcurrently() throws Exception {
        var executor = mock(LedgerImageCleanupExecutor.class);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(executor.processNext()).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return LedgerImageCleanupExecutor.Result.NO_TASK;
        });
        var worker = new LedgerImageCleanupWorker(executor);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(worker::runBatch);
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            worker.runBatch();
            release.countDown(); first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            verify(executor, times(1)).processNext();
        } finally { release.countDown(); pool.shutdownNow(); worker.stop(); }
    }
}
