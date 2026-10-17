package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelAtlasData.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.Media;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.Route;
import java.util.List;

public interface TravelAtlasStore {
    void requireActiveViewer(Long viewer);
    List<Plan> publishedCompletedPlans();
    List<Access> receivedCompletedPlans(Long viewer);
    List<Memory> memories(List<Long> plans);
    List<Photo> photos(List<Long> plans);
    List<Route> routes(List<Long> plans);
    /** Called only for freshly authorized cluster members, not an arbitrary browser media id. */
    Media detail(Long media);
    Window community(int page, int size);
    List<FeedPhoto> communityPhotos(List<Long> records);
    Hero hero(Long media);
}
