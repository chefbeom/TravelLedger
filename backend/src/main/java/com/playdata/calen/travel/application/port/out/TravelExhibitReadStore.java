package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelExhibitData.*;
import com.playdata.calen.travel.domain.model.TravelPlanDetailData;
import java.util.Optional;

public interface TravelExhibitReadStore {
    void requireActiveViewer(Long viewer);
    Page completedShares(Long viewer, int page, int size);
    Access receivedShare(Long viewer, Long share);
    Plan ownedPlan(Long owner, Long plan);
    TravelPlanDetailData.Detail planDetail(Long owner, Long plan);
    Attribution shareAttribution(Long share);
    Attribution ownerAttribution(Long plan);
    Optional<Media> media(Long media);
    Optional<PublicMedia> publicMedia(Long media);
}
