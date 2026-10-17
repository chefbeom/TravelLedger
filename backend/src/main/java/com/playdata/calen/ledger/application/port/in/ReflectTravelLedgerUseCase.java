package com.playdata.calen.ledger.application.port.in;

import com.playdata.calen.ledger.domain.model.LedgerTravelData.*;

public interface ReflectTravelLedgerUseCase {
    LedgerResult toLedger(Long owner, Long travelRecord);
    TravelResult toTravel(Long owner, Long plan, Long entry);
}
