package com.playdata.calen.ledger.application.port.in;

import com.playdata.calen.ledger.application.port.out.LedgerAiRecoveryPort.*;

/** Bounded recovery scans. Runtime ports retain the caller's cursors, queues and leases. */
public interface RecoverLedgerAiJobsUseCase {
    void recoverAnalysis(IdSource<AnalysisJob> source);
    void recoverImages(IdSource<ImageJob> source);
    void recoverWorkbooks(WorkbookSource source);
}
