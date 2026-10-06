package com.playdata.calen.ledger.repository;

public interface LedgerEmbeddingTrashOperations {
    int deleteAllDeletedByOwnerId(Long userId);
}
