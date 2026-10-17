package com.playdata.calen.recordmap.application.port.out;

import com.playdata.calen.recordmap.domain.model.RecordMapReadData.Scope;

public interface RecordMapReadAccessPort {
    Scope owned(Long owner, Long collectionId);
    Scope shared(String token);
    void requireOwnedPlace(Long owner, Long placeId);
}
