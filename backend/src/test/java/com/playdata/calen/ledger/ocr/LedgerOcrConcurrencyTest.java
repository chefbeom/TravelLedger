package com.playdata.calen.ledger.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.ledger.ai.LedgerAiRequestProperties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

class LedgerOcrConcurrencyTest {
    @Test
    void sixImageWorkersCanRunTogetherAndRejectOverflowWithoutLeakingThePool() throws Exception {
        var executor = new LedgerOcrAsyncConfig().ledgerOcrTaskExecutor(6, 0);
        var entered = new CountDownLatch(6);
        var release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 6; i++) executor.execute(() -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.getActiveCount()).isEqualTo(6);
            assertThatThrownBy(() -> executor.execute(() -> {})).isInstanceOf(TaskRejectedException.class);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void aiConnectDefaultsAdmitSixImagesWithoutTheOldFiveJobLimit() {
        assertThat(new LedgerOcrProperties().getMaxPendingPerUser()).isEqualTo(20);
        assertThat(new LedgerAiRequestProperties().getMaxConcurrent()).isEqualTo(20);
    }
}
