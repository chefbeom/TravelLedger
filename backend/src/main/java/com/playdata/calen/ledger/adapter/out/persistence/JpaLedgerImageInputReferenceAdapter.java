package com.playdata.calen.ledger.adapter.out.persistence;

import com.playdata.calen.ledger.application.port.out.LedgerImageInputReferencePort;
import com.playdata.calen.ledger.domain.model.LedgerImageStorageData.StoredImage;
import com.playdata.calen.ledger.repository.LedgerImageAnalysisRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JpaLedgerImageInputReferenceAdapter implements LedgerImageInputReferencePort {
    private final LedgerImageAnalysisRequestRepository requests;
    @Override public int attach(Long ownerId, Long analysisId, StoredImage image) {
        // Repository keeps the existing REQUIRES_NEW, owner predicate, version increment and status preservation.
        return requests.attachStoredImage(analysisId,ownerId,image.objectKey(),image.storedAt());
    }
}
