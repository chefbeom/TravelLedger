package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelReferenceData.Quote;
import java.util.Collection;
import java.util.List;

public interface TravelReferenceRatesPort {
    List<Quote> latest(Collection<String> currencies);
}
