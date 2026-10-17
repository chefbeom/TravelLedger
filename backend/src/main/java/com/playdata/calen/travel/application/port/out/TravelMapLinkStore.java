package com.playdata.calen.travel.application.port.out;

import com.playdata.calen.travel.domain.model.TravelMapLinkData.*;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.Marker;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.Route;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.PhotoPoint;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.Media;
import java.time.LocalDateTime;
import java.util.*;

public interface TravelMapLinkStore {
    void requireActiveOwner(Long owner);
    Set<Long> ownedPlans(Long owner, Collection<Long> ids);
    Set<Long> ownedRecords(Long owner, Set<Long> plans, Set<Long> ids);
    Set<Long> ownedMedia(Long owner, Set<Long> plans, Set<Long> ids);
    Set<Long> ownedRoutes(Long owner, Set<Long> plans, Set<Long> ids);
    Link save(Long owner, String token, String title, Scope scope, LocalDateTime now);
    Link active(String normalizedToken);
    List<Marker> markers(List<Long> plans);
    List<Asset> markerPhotos(List<Long> records);
    List<Route> routes(List<Long> plans);
    List<PhotoPoint> photos(Set<Long> plans, Scope scope);
    /** IDs are from a freshly authorized cluster, never caller-supplied media scopes. */
    List<Media> details(List<Long> ids);
    Asset asset(Long id);
}
