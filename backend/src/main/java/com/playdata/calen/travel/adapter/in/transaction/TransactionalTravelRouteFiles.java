package com.playdata.calen.travel.adapter.in.transaction;

import com.playdata.calen.travel.application.port.in.ManageTravelRouteFilesUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.port.out.TravelRouteUploadPort.Upload;
import com.playdata.calen.travel.application.service.ManageTravelRouteFilesService;
import com.playdata.calen.travel.domain.model.TravelRouteData.Result;
import com.playdata.calen.travel.domain.model.TravelRouteUploadData.*;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional(readOnly=true)
public class TransactionalTravelRouteFiles implements ManageTravelRouteFilesUseCase {
    private final ManageTravelRouteFilesUseCase delegate;
    public TransactionalTravelRouteFiles(TravelRouteStore routes,TravelRouteUploadPort storage,TravelSummaryInvalidationPort summaries) {
        delegate=new ManageTravelRouteFilesService(routes,storage,summaries);
    }
    @Override @Transactional public Result upload(Long owner,Long route,List<Upload> files){return delegate.upload(owner,route,files);}
    @Override public Prepared prepare(Long owner,Long route,List<Candidate> files){return delegate.prepare(owner,route,files);}
    @Override @Transactional public Result complete(Long owner,Long route,List<Completed> files){return delegate.complete(owner,route,files);}
    @Override public void abort(Long owner,Long route,List<String> keys){delegate.abort(owner,route,keys);}
}
