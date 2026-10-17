package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.domain.model.TravelMapLinkData.*;
import com.playdata.calen.travel.domain.model.TravelMediaData.Download;

public interface TravelMapLinksUseCase {
    Link create(Long owner, Draft draft);
    PublicMap map(String token);
    Cluster cluster(String token, Long cluster, Integer page, Integer size, Long focus);
    Download media(String token, Long media);
    Preview preview(String token);
    Download previewMedia(String token, int number);
}
