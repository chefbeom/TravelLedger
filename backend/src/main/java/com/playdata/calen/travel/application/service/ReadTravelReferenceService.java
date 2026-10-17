package com.playdata.calen.travel.application.service;

import com.playdata.calen.travel.application.port.in.ReadTravelReferenceUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.model.TravelReferenceData.*;
import com.playdata.calen.travel.domain.policy.TravelReferencePolicy;
import java.util.List;

public final class ReadTravelReferenceService implements ReadTravelReferenceUseCase {
    private final TravelReferenceStore store;
    private final TravelReferenceRatesPort rates;
    public ReadTravelReferenceService(TravelReferenceStore store, TravelReferenceRatesPort rates) { this.store = store; this.rates = rates; }
    @Override public Catalog catalog(Long owner) { store.requireActiveOwner(owner); return TravelReferencePolicy.catalog(store.catalogSource(owner)); }
    @Override public List<Quote> rates(Long owner, String input) { store.requireActiveOwner(owner); return rates.latest(TravelReferencePolicy.currencies(input)); }
}
