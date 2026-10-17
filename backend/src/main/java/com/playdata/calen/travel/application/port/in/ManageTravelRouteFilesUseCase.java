package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.application.port.out.TravelRouteUploadPort.Upload;
import com.playdata.calen.travel.domain.model.TravelRouteData.Result;
import com.playdata.calen.travel.domain.model.TravelRouteUploadData.*;
import java.util.List;

public interface ManageTravelRouteFilesUseCase {
    Result upload(Long owner,Long route,List<Upload> files);
    Prepared prepare(Long owner,Long route,List<Candidate> files);
    Result complete(Long owner,Long route,List<Completed> files);
    void abort(Long owner,Long route,List<String> objectKeys);
}
