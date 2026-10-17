package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.model.LedgerAnalysisHistoryData.Detail;
import java.time.LocalDateTime;
import java.util.Optional;

/** One request-local operation: keep validated owner/pending row private, not in a shared registry. */
public interface LedgerAnalysisAdmissionPort {
    void requireActiveOwner();
    void requireConfigured();
    boolean usesDistributedAdmission();
    AdmissionLease tryAdmission();
    String focusPrompt();
    LocalDateTime now();
    Optional<Detail> processing(LocalDateTime since);
    Optional<Detail> reusable(LocalDateTime since);
    void createProcessing();
    void submit();
    void submissionFailed(RuntimeException failure);
    /** Materialize only after submission, just as the original service did. */
    Detail pendingDetail();

    interface AdmissionLease extends AutoCloseable {
        @Override void close();
    }
}
