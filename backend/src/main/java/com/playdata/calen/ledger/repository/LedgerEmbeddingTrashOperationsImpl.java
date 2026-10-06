package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.embedding.sync.LedgerEmbeddingOutboxCapture;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;

/** Repository fragment keeps the bulk tombstone capture and delete in the caller's transaction. */
@RequiredArgsConstructor
public class LedgerEmbeddingTrashOperationsImpl implements LedgerEmbeddingTrashOperations {
    private final EntityManager entityManager;
    private final ObjectProvider<LedgerEmbeddingOutboxCapture> captureProvider;

    @Override
    @Transactional
    public int deleteAllDeletedByOwnerId(Long userId) {
        entityManager.flush();
        // JPA-only repository slices need not load the application-level embedding feature.
        LedgerEmbeddingOutboxCapture capture = captureProvider.getIfAvailable();
        if (capture != null) capture.beforeEmptyTrash(userId);
        return entityManager.createQuery("delete from LedgerEntry entry where entry.owner.id = :userId and entry.deletedAt is not null")
                .setParameter("userId", userId).executeUpdate();
    }
}
