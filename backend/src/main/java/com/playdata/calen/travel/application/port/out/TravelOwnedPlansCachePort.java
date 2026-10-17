package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelOwnedPlansData.*;
import java.util.List;

public interface TravelOwnedPlansCachePort {
    List<Summary> plans(Long owner);
    Portfolio portfolio(Long owner);
    void publishPlans(Long owner, List<Summary> plans);
    void publishPortfolio(Long owner, Portfolio portfolio);
}
