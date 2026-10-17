package com.playdata.calen.travel.application.port.out;

public interface TravelClusterDetailSynchronizationPort {
    void awaitCurrent(Long owner);
    void lockManualRefresh(Long owner);
    void refreshMissing(Long owner);
    void refreshNow(Long owner);
    void requestRefresh(Long owner);
}
