package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelReferenceData.Source;

public interface TravelReferenceStore {
    void requireActiveOwner(Long owner);
    Source catalogSource(Long owner);
}
