package com.playdata.calen.ledger.embedding.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.playdata.calen.common.embedding.EmbeddingProperties;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.ledger.application.port.in.SynchronizeLedgerEmbeddingsUseCase;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LedgerEmbeddingSyncWorkerTest {
    final EmbeddingProperties api=new EmbeddingProperties();
    final LedgerEmbeddingSyncProperties options=new LedgerEmbeddingSyncProperties();
    final SynchronizeLedgerEmbeddingsUseCase batches=mock(SynchronizeLedgerEmbeddingsUseCase.class);
    final WorkLeaseService leases=mock(WorkLeaseService.class);
    final WorkLeaseService.Lease lease=mock(WorkLeaseService.Lease.class);
    @Test void disabledWorkerDoesNotAcquireOrScheduleAnyWork() {
        var worker=worker();try{worker.poll();ReflectionTestUtils.invokeMethod(worker,"runBatch");verifyNoInteractions(leases,batches);}finally{worker.stop();}
    }
    @Test void batchAcquiresTheSameSharedLeaseAndClosesItAfterSuccess() {
        api.setEnabled(true);when(leases.tryAcquire("ledger-embedding:api-worker:v1",options.getLeaseDuration())).thenReturn(lease);
        when(lease.isValid()).thenReturn(true);
        doAnswer(call->{
            var execution=call.getArgument(1,SynchronizeLedgerEmbeddingsUseCase.Execution.class);
            assertThat(execution.enabled()).isTrue();assertThat(execution.leaseValid()).isTrue();execution.assertNetworkReady();
            api.setEnabled(false);assertThat(execution.enabled()).isFalse();return null;
        }).when(batches).run(eq(4),any());
        var worker=worker();try{ReflectionTestUtils.invokeMethod(worker,"runBatch");verify(batches).run(eq(4),any());verify(lease).close();}finally{worker.stop();}
    }
    @Test void noLeaseMeansNoBatch() {
        api.setEnabled(true);var worker=worker();try{ReflectionTestUtils.invokeMethod(worker,"runBatch");verifyNoInteractions(batches);}finally{worker.stop();}
    }
    @Test void runtimeFailureClosesLeaseAndTransactionGuardRemainsInTheWorkerAdapter() {
        api.setEnabled(true);when(leases.tryAcquire(anyString(),any())).thenReturn(lease);
        doAnswer(call->{call.getArgument(1,SynchronizeLedgerEmbeddingsUseCase.Execution.class).assertNetworkReady();return null;}).when(batches).run(anyInt(),any());
        var worker=worker();TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(()->ReflectionTestUtils.invokeMethod(worker,"runBatch")).hasMessage("embedding network calls must run outside a database transaction");
            verify(lease).close();
        } finally {TransactionSynchronizationManager.setActualTransactionActive(false);worker.stop();}
    }
    @Test void pollReturnsImmediatelyPreventsOverlappingCyclesAndReleasesBusyFlagAfterFailure() throws Exception {
        api.setEnabled(true);when(leases.tryAcquire(anyString(),any())).thenReturn(lease);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test latch");throw new IllegalStateException("synthetic");})
                .when(batches).run(anyInt(),any());
        var worker=worker();
        try {
            worker.poll();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();worker.poll();verify(batches,times(1)).run(anyInt(),any());
            release.countDown();
            var busy=(AtomicBoolean)ReflectionTestUtils.getField(worker,"busy");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(busy.get() && System.nanoTime()<deadline) Thread.sleep(5);
            assertThat(busy.get()).isFalse();verify(lease).close();
        } finally {release.countDown();worker.stop();}
    }
    @Test void stoppedExecutorRejectsWorkWithoutLeavingBusyStuck() {
        api.setEnabled(true);var worker=worker();worker.stop();worker.poll();
        assertThat(((AtomicBoolean)ReflectionTestUtils.getField(worker,"busy")).get()).isFalse();verifyNoInteractions(batches,leases);
    }
    @Test void originalLeaseBudgetValidationStillRunsAtConstruction() {
        api.setEnabled(true);options.setLeaseDuration(Duration.ofSeconds(1));
        assertThatThrownBy(this::worker).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lease must exceed");
    }
    LedgerEmbeddingSyncWorker worker() {return new LedgerEmbeddingSyncWorker(api,options,batches,leases);}
}
