package com.playdata.calen.ledger.application.service;

import com.playdata.calen.ledger.application.port.in.RecoverLedgerAiJobsUseCase;
import com.playdata.calen.ledger.application.port.out.LedgerAiRecoveryPort.*;

/** Common bounded traversal, preserving each job family's existing admission and cursor semantics. */
public final class RecoverLedgerAiJobsService implements RecoverLedgerAiJobsUseCase {
    private static final int MAX_BATCHES = 5;
    private static final int PAGE_SIZE = 100;

    @Override
    public void recoverAnalysis(IdSource<AnalysisJob> source) {
        if (!source.available()) return;
        long cursor = source.cursor();
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            var jobs = source.page(cursor, PAGE_SIZE);
            if (jobs.isEmpty()) { source.remember(0); return; }
            cursor = jobs.get(jobs.size() - 1).id();
            for (var job : jobs) {
                source.remember(job.id());
                if (job.running() || job.atCapacity()) continue;
                if (!job.reconstructable()) { job.rejectUnrecoverable(); continue; }
                if (!job.trySubmit()) return;
            }
            if (jobs.size() < PAGE_SIZE) { source.remember(0); return; }
        }
    }

    @Override
    public void recoverImages(IdSource<ImageJob> source) {
        if (!source.available()) return;
        long cursor = source.cursor();
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            var jobs = source.page(cursor, PAGE_SIZE);
            if (jobs.isEmpty()) { source.remember(0); return; }
            cursor = jobs.get(jobs.size() - 1).id();
            for (var job : jobs) {
                source.remember(job.id());
                if (job.running()) continue;
                if (!job.reconstructable()) { job.rejectUnrecoverable(); continue; }
                if (!job.tryReserve()) continue;
                if (!job.trySubmit()) return;
            }
            if (jobs.size() < PAGE_SIZE) { source.remember(0); return; }
        }
    }

    @Override
    public void recoverWorkbooks(WorkbookSource source) {
        WorkbookCursor cursor = source.cursor();
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            var jobs = source.page(cursor, PAGE_SIZE);
            for (var job : jobs) {
                cursor = job.cursor();
                source.remember(cursor);
                if (job.running()) continue;
                if (!job.trySubmit()) return;
            }
            if (jobs.size() < PAGE_SIZE) { source.remember(new WorkbookCursor(null, "")); return; }
        }
    }
}
