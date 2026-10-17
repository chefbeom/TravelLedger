package com.playdata.calen.travel.application.port.out;

public interface TravelSummaryInvalidationPort {
    void invalidateOwner(Long owner);
    void invalidatePublic();
}
