package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.TravelRouteSourceType;
import com.playdata.calen.travel.domain.model.TravelRouteData.*;
import java.util.List;

public interface TravelRoutePayloadPort {
    int characterBudget();
    String encode(List<Point> points, TravelRouteSourceType source);
    List<Point> decode(String path);
    List<Point> resolve(TravelRouteSourceType source, String files, String path);
    String encodeFiles(List<GpxFile> files);
    List<GpxFile> files(String metadata);
    void deleteFiles(String metadata);
}
