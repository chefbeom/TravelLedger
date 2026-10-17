package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.model.LedgerImageStorageData.StoredImage;

public interface LedgerImageInputReferencePort {
    /** Short, owner-scoped reference update; includes cancelled jobs and never merges a detached job. */
    int attach(Long ownerId, Long analysisId, StoredImage image);
}
