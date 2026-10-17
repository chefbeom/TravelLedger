package com.playdata.calen.travel.application.service;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.application.port.in.ManageTravelRouteFilesUseCase;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.application.port.out.TravelRouteUploadPort.Upload;
import com.playdata.calen.travel.domain.model.TravelRouteData.*;
import com.playdata.calen.travel.domain.model.TravelRouteUploadData.*;
import java.util.*;

public final class ManageTravelRouteFilesService implements ManageTravelRouteFilesUseCase {
    private final TravelRouteStore routes;
    private final TravelRouteUploadPort storage;
    private final TravelSummaryInvalidationPort summaries;
    public ManageTravelRouteFilesService(TravelRouteStore routes,TravelRouteUploadPort storage,TravelSummaryInvalidationPort summaries) {
        this.routes=routes;this.storage=storage;this.summaries=summaries;
    }
    @Override public Result upload(Long owner,Long route,List<Upload> files) {
        var access=routes.ownedRoute(owner,route);
        if(files==null||files.isEmpty())throw new BadRequestException("Select at least one GPX file.");
        // Deliberately preserve legacy direct-upload order and its existing compensation limits.
        routes.removeFilesAndLinks(route);
        List<GpxFile> stored=new ArrayList<>();
        for(var file:files)stored.add(storage.store(owner,access.plan().id(),route,file));
        return replace(owner,route,stored);
    }
    @Override public Prepared prepare(Long owner,Long route,List<Candidate> files) {
        var access=routes.ownedRoute(owner,route);
        requirePresigned();
        return new Prepared("PRESIGNED",storage.prepare(owner,access.plan().id(),route,files));
    }
    @Override public Result complete(Long owner,Long route,List<Completed> files) {
        var access=routes.ownedRoute(owner,route);
        requirePresigned();
        // Every new object must be validated before removing previous links/files.
        var stored=files.stream().map(file->storage.complete(owner,access.plan().id(),route,file)).toList();
        routes.removeFilesAndLinks(route);
        return replace(owner,route,stored);
    }
    @Override public void abort(Long owner,Long route,List<String> keys) {
        var access=routes.ownedRoute(owner,route);
        storage.abort(owner,access.plan().id(),route,keys);
    }
    private Result replace(Long owner,Long route,List<GpxFile> files) {
        routes.replaceFiles(route,files);
        var result=routes.result(route);
        summaries.invalidateOwner(owner);
        return result;
    }
    private void requirePresigned() {
        if(!storage.supportsPresigned())throw new BadRequestException("Travel GPX uploads require presigned object storage.");
    }
}
