package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.EntryType;
import com.playdata.calen.ledger.domain.model.LedgerPreparationData.*;

public interface LedgerReferencesPort {
    Category category(Long ownerId, Long id);
    Detail detail(Long ownerId, Long id);
    Payment payment(Long ownerId, EntryType type, Long id);
    TravelPeriod travelPeriod(Long ownerId, Long planId);
}
