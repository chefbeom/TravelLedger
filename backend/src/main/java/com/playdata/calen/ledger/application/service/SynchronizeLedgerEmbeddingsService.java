package com.playdata.calen.ledger.application.service;

import com.playdata.calen.ledger.application.port.in.SynchronizeLedgerEmbeddingsUseCase;
import com.playdata.calen.ledger.application.port.out.*;
import com.playdata.calen.ledger.domain.model.LedgerEmbeddingSyncData.*;
import java.util.*;

/** One already-leased batch. Every storage call retains its independent short transaction. */
public final class SynchronizeLedgerEmbeddingsService implements SynchronizeLedgerEmbeddingsUseCase {
    private final LedgerEmbeddingJobsPort jobs;
    private final LedgerEmbeddingIndexPort index;

    public SynchronizeLedgerEmbeddingsService(LedgerEmbeddingJobsPort jobs, LedgerEmbeddingIndexPort index) {
        this.jobs = jobs;
        this.index = index;
    }

    @Override public void run(int batchSize, Execution execution) {
        jobs.recoverExpired();
        List<Prepared> upserts = new ArrayList<>(), deletes = new ArrayList<>();
        for (Claim claim : jobs.claimBatch(batchSize)) {
            if (!execution.leaseValid()) return;
            try {
                Prepared prepared = jobs.prepareLatest(claim);
                if (prepared == null) continue;
                (prepared.document() == null ? deletes : upserts).add(prepared);
            } catch (IllegalArgumentException invalidDocument) { jobs.failed(claim, false, "INVALID_SOURCE"); }
            catch (RuntimeException sourceFailure) { jobs.failed(claim, true, "SOURCE_READ_FAILED"); }
        }
        send(upserts, false, execution);
        send(deletes, true, execution);
    }

    private void send(List<Prepared> prepared, boolean delete, Execution execution) {
        if (!execution.leaseValid() || !execution.enabled()) return;
        List<Prepared> current = prepared.stream().filter(item -> {
            if (jobs.isCurrent(item.claim())) return true;
            jobs.releaseSuperseded(item.claim());
            return false;
        }).toList();
        if (current.isEmpty() || !execution.leaseValid()) return;
        // Keep this precondition outside the API catch: runtime misuse must not mark jobs as API failures.
        execution.assertNetworkReady();
        try {
            if (delete) index.delete(current.stream().map(item -> item.claim().key()).toList());
            else index.upsert(current.stream().map(Prepared::document).toList());
        } catch (LedgerEmbeddingIndexPort.Failure failure) {
            String code = failure.httpStatus() == null
                    ? (failure.retryable() ? "API_UNAVAILABLE" : "API_CONTRACT_ERROR") : "HTTP_" + failure.httpStatus();
            failBatch(current, failure.retryable(), code, execution);
            return;
        } catch (IllegalArgumentException invalidRequest) {
            failBatch(current, false, "INVALID_DOCUMENT", execution);
            return;
        } catch (RuntimeException unknownFailure) {
            failBatch(current, true, "API_CALL_FAILED", execution);
            return;
        }
        // The store's revision/token/lease predicate must remain the final acknowledgement guard.
        if (execution.leaseValid()) for (Prepared item : current) jobs.succeeded(item.claim());
    }

    private void failBatch(List<Prepared> batch, boolean retryable, String code, Execution execution) {
        if (!execution.leaseValid()) return;
        for (Prepared item : batch) jobs.failed(item.claim(), retryable, code);
        execution.reportFailure(code, batch.size(), retryable);
    }
}
