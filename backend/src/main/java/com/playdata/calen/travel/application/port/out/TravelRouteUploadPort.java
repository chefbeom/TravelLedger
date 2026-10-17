package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelRouteData.GpxFile;
import com.playdata.calen.travel.domain.model.TravelRouteUploadData.*;
import java.io.IOException;
import java.util.List;

public interface TravelRouteUploadPort {
    boolean supportsPresigned();
    GpxFile store(Long owner,Long plan,Long route,Upload file);
    List<Target> prepare(Long owner,Long plan,Long route,List<Candidate> files);
    GpxFile complete(Long owner,Long plan,Long route,Completed file);
    void abort(Long owner,Long plan,Long route,List<String> objectKeys);

    /** Lazy original getBytes contract; only adapters read bytes and translate IO failures. */
    interface Upload {
        String name();
        String fileName();
        String contentType();
        long size();
        boolean empty();
        byte[] bytes() throws IOException;
    }
}
