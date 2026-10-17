package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelRecordData.*;
import java.util.Optional;

public interface TravelRecordStore {
    Plan ownedPlan(Long owner, Long planId);
    Optional<Access> ownedRecord(Long owner, Long recordId);
    Long create(Long planId, Value value);
    void update(Long recordId, Value value);
    void removeMedia(Long owner, Long recordId);
    void delete(Long recordId);
}
