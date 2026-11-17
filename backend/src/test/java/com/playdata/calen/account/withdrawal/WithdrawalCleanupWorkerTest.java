package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.withdrawal.KakaoWithdrawalCleanupExecutor.Result;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WithdrawalCleanupWorkerTest {
    @Test void workerIsNotCreatedUnlessBothInternalTestFlagsAreExplicitlyEnabled() {
        var context = new ApplicationContextRunner()
                .withBean(KakaoWithdrawalCleanupExecutor.class, () -> mock(KakaoWithdrawalCleanupExecutor.class))
                .withUserConfiguration(WithdrawalCleanupWorker.class);
        context.run(application -> assertThat(application).doesNotHaveBean(WithdrawalCleanupWorker.class));
        context.withPropertyValues("app.account-withdrawal.internal-workflow-enabled=true")
                .run(application -> assertThat(application).doesNotHaveBean(WithdrawalCleanupWorker.class));
        context.withPropertyValues("app.account-withdrawal.cleanup-worker-enabled=true")
                .run(application -> assertThat(application).doesNotHaveBean(WithdrawalCleanupWorker.class));
        context.withPropertyValues("app.account-withdrawal.internal-workflow-enabled=true",
                        "app.account-withdrawal.cleanup-worker-enabled=true")
                .run(application -> assertThat(application).hasSingleBean(WithdrawalCleanupWorker.class));
    }

    @Test void processStopsWhenThereIsNoMoreWorkAndBoundsEachCycle() {
        var executor = mock(KakaoWithdrawalCleanupExecutor.class);
        var worker = new WithdrawalCleanupWorker(executor);
        try {
            when(executor.processNext()).thenReturn(Result.SUCCEEDED, Result.RETRY_PENDING, Result.NO_TASK);
            worker.runBatch();
            verify(executor, times(3)).processNext();

            var busyExecutor = mock(KakaoWithdrawalCleanupExecutor.class);
            when(busyExecutor.processNext()).thenReturn(Result.SUCCEEDED);
            var busyWorker = new WithdrawalCleanupWorker(busyExecutor);
            try {
                busyWorker.runBatch();
                verify(busyExecutor, times(5)).processNext();
            } finally { busyWorker.stop(); }
        } finally { worker.stop(); }
    }

    @Test void configurationErrorsAndExceptionsStopOnlyTheCurrentCycle() {
        var executor = mock(KakaoWithdrawalCleanupExecutor.class);
        var worker = new WithdrawalCleanupWorker(executor);
        try {
            when(executor.processNext()).thenReturn(Result.CONFIGURATION_REQUIRED)
                    .thenThrow(new IllegalStateException("sensitive provider details"))
                    .thenReturn(Result.NO_TASK);
            worker.runBatch();
            worker.runBatch();
            worker.runBatch();
            verify(executor, times(3)).processNext();
        } finally { worker.stop(); }
    }

    @Test void overlappingCyclesCannotStartAnotherProviderCall() throws Exception {
        var executor = mock(KakaoWithdrawalCleanupExecutor.class);
        var worker = new WithdrawalCleanupWorker(executor);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            when(executor.processNext()).thenAnswer(invocation -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                return Result.NO_TASK;
            });
            var running = pool.submit(worker::runBatch);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.runBatch();
            verify(executor, times(1)).processNext();
            release.countDown();
            running.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            worker.stop();
        }
    }
}
