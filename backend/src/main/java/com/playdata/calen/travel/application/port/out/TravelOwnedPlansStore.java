package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelOwnedPlansData.*;
import com.playdata.calen.travel.domain.model.TravelPlanDetailData.Detail;
import java.util.List;

public interface TravelOwnedPlansStore {
    void requireActiveOwner(Long owner);
    List<Aggregate> aggregates(Long owner);
    Detail detail(Long owner, Long plan);
    Rows portfolio(Long owner);
}
