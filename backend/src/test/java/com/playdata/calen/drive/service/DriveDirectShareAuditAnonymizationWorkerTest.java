package com.playdata.calen.drive.service;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DriveDirectShareAuditAnonymizationWorkerTest {
    @Test void eachRunHasABoundedNumberOfTransactions() {
        var anonymizer = mock(DriveDirectShareAuditAnonymizer.class);
        when(anonymizer.anonymizeBatch(250)).thenReturn(250);
        var worker = new DriveDirectShareAuditAnonymizationWorker(anonymizer);
        ReflectionTestUtils.setField(worker, "enabled", true);
        worker.runBatch();
        verify(anonymizer, times(10)).anonymizeBatch(250);
    }

    @Test void stopsWhenCaughtUpAndCanRetryAfterAStorageFailure() {
        var anonymizer = mock(DriveDirectShareAuditAnonymizer.class);
        when(anonymizer.anonymizeBatch(250)).thenThrow(new IllegalStateException("sensitive test value"))
                .thenReturn(1);
        var worker = new DriveDirectShareAuditAnonymizationWorker(anonymizer);
        ReflectionTestUtils.setField(worker, "enabled", true);
        worker.runBatch(); worker.runBatch();
        verify(anonymizer, times(2)).anonymizeBatch(250);
    }

    @Test void disablingOnlyTheBackfillDoesNotRunBatches() {
        var anonymizer = mock(DriveDirectShareAuditAnonymizer.class);
        var worker = new DriveDirectShareAuditAnonymizationWorker(anonymizer);
        ReflectionTestUtils.setField(worker, "enabled", false);
        worker.runBatch();
        verifyNoInteractions(anonymizer);
    }
}
