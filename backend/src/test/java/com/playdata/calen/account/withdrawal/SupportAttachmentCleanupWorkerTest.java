package com.playdata.calen.account.withdrawal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SupportAttachmentCleanupWorkerTest {
    @Test void workerRequiresBothExplicitInternalFlags() {
        var context = new ApplicationContextRunner()
                .withBean(SupportAttachmentCleanupExecutor.class, () -> mock(SupportAttachmentCleanupExecutor.class))
                .withUserConfiguration(SupportAttachmentCleanupWorker.class);
        context.run(application -> assertThat(application).doesNotHaveBean(SupportAttachmentCleanupWorker.class));
        context.withPropertyValues("app.account-withdrawal.internal-workflow-enabled=true")
                .run(application -> assertThat(application).doesNotHaveBean(SupportAttachmentCleanupWorker.class));
        context.withPropertyValues("app.account-withdrawal.support-cleanup-worker-enabled=true")
                .run(application -> assertThat(application).doesNotHaveBean(SupportAttachmentCleanupWorker.class));
        context.withPropertyValues("app.account-withdrawal.internal-workflow-enabled=true",
                        "app.account-withdrawal.support-cleanup-worker-enabled=true")
                .run(application -> assertThat(application).hasSingleBean(SupportAttachmentCleanupWorker.class));
    }

    @Test void workerBoundsEachCycleAndExecutorRejectsAnOuterTransaction() {
        var executor = mock(SupportAttachmentCleanupExecutor.class);
        var worker = new SupportAttachmentCleanupWorker(executor);
        try {
            when(executor.processNext()).thenReturn(SupportAttachmentCleanupExecutor.Result.SUCCEEDED);
            worker.runBatch();
            verify(executor, times(5)).processNext();
        } finally { worker.stop(); }
        var store = mock(WithdrawalCleanupTaskStore.class);
        var handler = mock(SupportAttachmentCleanupHandler.class);
        var actual = new SupportAttachmentCleanupExecutor(store, handler);
        when(store.claimNext(Set.of(WithdrawalCleanupKind.SUPPORT_ATTACHMENT), Duration.ofMinutes(5))).thenReturn(Optional.empty());
        assertThat(actual.processNext()).isEqualTo(SupportAttachmentCleanupExecutor.Result.NO_TASK);
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(actual::processNext).isInstanceOf(IllegalStateException.class);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
