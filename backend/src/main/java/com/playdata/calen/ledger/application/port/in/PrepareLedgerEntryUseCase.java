package com.playdata.calen.ledger.application.port.in;

import com.playdata.calen.ledger.domain.model.LedgerPreparationData.Draft;
import com.playdata.calen.ledger.domain.model.LedgerPreparationData.Prepared;

public interface PrepareLedgerEntryUseCase {
    Prepared prepare(Long ownerId, Draft draft, boolean sharingPreview);
}
