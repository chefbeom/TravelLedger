package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.model.LedgerEmbeddingSyncData.*;
import java.util.List;

public interface LedgerEmbeddingIndexPort {
    void upsert(List<Document> documents);
    void delete(List<Key> sources);

    /** Deliberately carries no raw cause, request body, response body or provider message. */
    final class Failure extends RuntimeException {
        private final Integer httpStatus;
        private final boolean retryable;
        public Failure(Integer httpStatus, boolean retryable) {
            super("Embedding index call failed.");
            this.httpStatus = httpStatus;
            this.retryable = retryable;
        }
        public Integer httpStatus() { return httpStatus; }
        public boolean retryable() { return retryable; }
    }
}
