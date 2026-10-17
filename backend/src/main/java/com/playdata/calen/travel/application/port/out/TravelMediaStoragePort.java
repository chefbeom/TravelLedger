package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelMediaData.*;
import java.util.List;

public interface TravelMediaStoragePort {
    boolean supportsPresigned();
    List<Target> prepare(Long owner, Long plan, Long record, List<Candidate> files);
    Stored complete(Long owner, Long plan, Long record, Completed file);
    void delete(String storagePath, String contentType);
}
