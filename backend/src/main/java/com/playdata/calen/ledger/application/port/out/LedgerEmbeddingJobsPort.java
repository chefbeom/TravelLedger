package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.model.LedgerEmbeddingSyncData.*;
import java.util.List;

public interface LedgerEmbeddingJobsPort {
    void recoverExpired();
    List<Claim> claimBatch(int limit);
    Prepared prepareLatest(Claim claim);
    boolean isCurrent(Claim claim);
    void releaseSuperseded(Claim claim);
    void succeeded(Claim claim);
    void failed(Claim claim, boolean retryable, String code);
}
