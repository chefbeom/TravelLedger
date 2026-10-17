package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.domain.model.TravelOwnedPlansData.*;
import com.playdata.calen.travel.domain.model.TravelPlanDetailData.Detail;
import java.util.List;

public interface ReadOwnedTravelPlansUseCase {
    List<Summary> plans(Long owner);
    Detail detail(Long owner, Long plan);
    Portfolio portfolio(Long owner);
}
