package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.Media;
import java.util.List;

public interface TravelMediaStore {
    void requireUser(Long owner);
    Access ownedRecord(Long owner, Long record, TravelRecordType expected);
    Long save(Long owner, Long record, TravelMediaType type, String caption, Stored stored, Gps gps);
    void link(List<Long> media);
    List<Media> media(List<Long> media);
    Owned ownedMedia(Long owner, Long media);
    Download download(Long owner, Long media);
    void removeLink(Long media);
    void delete(Long media);
}
