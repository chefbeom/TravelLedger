package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.application.port.in.ReadTravelReferenceUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.service.ReadTravelReferenceService;
import com.playdata.calen.travel.domain.model.TravelReferenceData.*;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly = true)
public class TransactionalTravelReference implements ReadTravelReferenceUseCase {
    private final ReadTravelReferenceService delegate;
    public TransactionalTravelReference(TravelReferenceStore store, TravelReferenceRatesPort rates) { delegate = new ReadTravelReferenceService(store, rates); }
    @Override public Catalog catalog(Long owner) { return delegate.catalog(owner); }
    @Override public List<Quote> rates(Long owner, String currencies) { return delegate.rates(owner, currencies); }
}
