package com.playdata.calen.ledger.adapter.out.persistence;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.ledger.application.port.out.LedgerImageInputReferencePort;
import com.playdata.calen.ledger.domain.model.LedgerImageStorageData.StoredImage;
import com.playdata.calen.ledger.repository.LedgerImageAnalysisRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class JpaLedgerImageInputReferenceAdapter implements LedgerImageInputReferencePort {
    private final LedgerImageAnalysisRequestRepository requests;
    private final AppUserRepository users;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Override public int attach(Long ownerId, Long analysisId, StoredImage image) {
        // Use the same lock order as withdrawal; the reference update joins this short transaction.
        if (users.findActiveForPreferenceUpdate(ownerId).isEmpty()) return 0;
        if (image.bucket() == null || image.bucket().isBlank()) {
            return requests.attachStoredImage(analysisId, ownerId, image.objectKey(), image.storedAt());
        }
        return requests.attachStoredImageWithBucket(analysisId, ownerId, image.objectKey(), image.bucket(),
                image.storageId(), image.versionId(), image.etag(), image.storedAt());
    }
}
