package com.playdata.calen.ledger.application.port.in;

public interface SynchronizeLedgerEmbeddingsUseCase {
    void run(int batchSize, Execution execution);

    /** Per-batch runtime controls owned by the scheduler adapter, not persisted business state. */
    interface Execution {
        boolean enabled();
        boolean leaseValid();
        void assertNetworkReady();
        void reportFailure(String code, int count, boolean retryable);
    }
}
