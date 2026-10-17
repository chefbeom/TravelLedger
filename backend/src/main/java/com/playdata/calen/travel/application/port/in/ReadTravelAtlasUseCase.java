package com.playdata.calen.travel.application.port.in;

import com.playdata.calen.travel.domain.model.TravelAtlasData.*;

public interface ReadTravelAtlasUseCase {
    Overview overview(Long viewer);
    Cluster cluster(Long viewer, Long cluster, Integer page, Integer size, Long focus);
    Feed community(Long viewer, Integer page, Integer size);
}
