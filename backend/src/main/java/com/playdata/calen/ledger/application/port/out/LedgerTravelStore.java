package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.model.LedgerTravelData.*;
import java.util.Optional;

public interface LedgerTravelStore {
    Optional<Travel> ownedTravel(Long owner, Long record);
    Optional<Plan> ownedPlan(Long owner, Long plan);
    Optional<Entry> ownedEntry(Long owner, Long entry);
    Optional<LinkedTravel> linkedTravel(Long owner, Long record);
    Optional<Long> linkedLedger(Long owner, Long record);
    Long group(Long owner, String name);
    Long detail(Long group, String lookupName, String createName);
    Long payment(Long owner, String name);
    Long saveTravel(Long plan, Long existing, TravelValue value);
    TravelResult travelResult(Long record);
}
