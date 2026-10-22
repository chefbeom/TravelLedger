package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.model.LedgerAiJobData.*;

/** Each call must atomically check owner + PROCESSING, increment version and commit independently.
 * Image and Excel finalization additionally check the pre-inference version.
 * Never recreate missing jobs or overwrite terminal jobs. */
public interface LedgerAiJobStatePort {
    int completeProcessing(Completion completion);
    int failProcessing(Failure failure);
    int cancelProcessing(Cancellation cancellation);
}
