package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.domain.model.TravelReferenceData.*;
import java.util.List;

public interface ReadTravelReferenceUseCase {
    Catalog catalog(Long owner);
    List<Quote> rates(Long owner, String currencies);
}
