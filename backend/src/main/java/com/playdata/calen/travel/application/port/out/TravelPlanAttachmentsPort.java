package com.playdata.calen.travel.application.port.out;

public interface TravelPlanAttachmentsPort {
    void removePlanLinks(Long planId);
    void removeMedia(Long owner, Long planId);
    void removeRoutes(Long owner, Long planId);
}
