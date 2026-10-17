package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.application.port.in.WriteTravelRouteUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.service.WriteTravelRouteService;
import com.playdata.calen.travel.domain.model.TravelRouteData.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class TransactionalTravelRoutes implements WriteTravelRouteUseCase {
    private final WriteTravelRouteUseCase delegate;
    public TransactionalTravelRoutes(TravelRouteStore routes,TravelRoutePayloadPort payload,TravelSummaryInvalidationPort summaries) {
        delegate=new WriteTravelRouteService(routes,payload,summaries);
    }
    @Override public Result create(Long owner,Long plan,Draft draft){return delegate.create(owner,plan,draft);}
    @Override public Result update(Long owner,Long id,Draft draft){return delegate.update(owner,id,draft);}
    @Override public void delete(Long owner,Long id){delegate.delete(owner,id);}
}
