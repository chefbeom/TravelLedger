package com.playdata.calen.account.withdrawal;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UnknownLoginAuditPrivacyWorkerTest {
    @Test void processesOnlyABoundedNumberOfBatchesAndRetriesLater() {
        var backfill = mock(UnknownLoginAuditPrivacyBackfill.class);
        var worker = new UnknownLoginAuditPrivacyWorker(backfill);
        ReflectionTestUtils.setField(worker, "enabled", true);
        when(backfill.classifyBatch(250)).thenReturn(250);
        worker.runBatch();
        verify(backfill, times(10)).classifyBatch(250);
        reset(backfill);
        when(backfill.classifyBatch(250)).thenThrow(new IllegalStateException("private")).thenReturn(0);
        worker.runBatch(); worker.runBatch();
        verify(backfill, times(2)).classifyBatch(250);
        worker.stop();
    }

    @Test void disablingTheWorkerDoesNotStartHistoricalCleanup() {
        var backfill = mock(UnknownLoginAuditPrivacyBackfill.class);
        var worker = new UnknownLoginAuditPrivacyWorker(backfill);
        ReflectionTestUtils.setField(worker, "enabled", false);
        worker.runBatch();
        verifyNoInteractions(backfill);
        worker.stop();
    }
}
