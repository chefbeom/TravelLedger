package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.domain.model.TravelClusterDetailData.Page;

public interface ManageTravelPhotoClustersUseCase {
    Page page(Long owner, Long cluster, Integer page, Integer size, Long focusMedia);
    Page changeRepresentative(Long owner, Long cluster, Long media);
}
