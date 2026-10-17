package com.playdata.calen.travel.application.port.out;

public interface TravelClusterRefreshPort {
    void refreshAfterChange(Long owner);
}
