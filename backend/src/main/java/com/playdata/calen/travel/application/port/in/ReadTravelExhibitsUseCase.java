package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.domain.model.TravelExhibitData.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.Download;

public interface ReadTravelExhibitsUseCase {
    Page received(Long viewer, Integer page, Integer size);
    Detail receivedDetail(Long viewer, Long share);
    Download receivedMedia(Long viewer, Long share, Long media);
    Download publicMedia(Long media, String token);
    /** Internal bridge only: the caller must verify a current, accepted record-group share first. */
    Detail recordShareDetail(Long owner, Long plan, Long share);
    /** Internal bridge only: no controller may accept an owner/plan scope supplied by the browser. */
    Download recordShareMedia(Long owner, Long plan, Long media);
}
