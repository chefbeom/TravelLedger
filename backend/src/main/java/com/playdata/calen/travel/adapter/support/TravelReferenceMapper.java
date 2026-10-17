package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.domain.model.TravelReferenceData.*;
import com.playdata.calen.travel.dto.*;

public final class TravelReferenceMapper {
    private TravelReferenceMapper() {}
    public static TravelCategoryCatalogResponse catalog(Catalog c) { return new TravelCategoryCatalogResponse(c.statuses(), c.budgets(), c.expenses(), c.memories(), c.countries(), c.regions(), c.places()); }
    public static TravelExchangeRateResponse quote(Quote q) { return new TravelExchangeRateResponse(q.currency(), q.rate(), q.date(), q.available(), q.provider()); }
}
