package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.Media;
import java.util.List;

public interface ManageOwnedTravelMediaUseCase {
    Prepared prepare(Long owner, Long record, TravelRecordType expected, List<Candidate> files);
    List<Media> complete(Long owner, Long record, TravelRecordType expected, TravelMediaType requested, String caption, List<Completed> files);
    Download download(Long owner, Long media);
    void delete(Long owner, Long media);
}
