package com.playdata.calen.recordmap.adapter.support;

import com.playdata.calen.recordmap.domain.model.RecordMapReadData.*;
import com.playdata.calen.recordmap.dto.RecordMapDtos.*;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** Only this boundary knows public routes, protocol ID prefixes and legacy HTTP DTOs. */
public final class RecordMapReadMapper {
    private RecordMapReadMapper() {}

    public static Overview response(OverviewResult result, String token) {
        return new Overview(result.collections().stream().map(c -> new CollectionView(c.id(), c.name(), c.memo(), c.colorHex(), c.placeCount(), c.shared())).toList(),
                result.places().stream().map(p -> new PlaceView(p.id(), p.name(), p.memo(), p.country(), p.region(), p.latitude(), p.longitude(),
                        p.visited(), p.visitCount(), p.sourceTravelRecordId(), p.collectionIds())).toList(),
                visits(result.visits(), token), result.totalPlaces(), result.page(), result.totalPages(), result.visitsLimited(),
                result.readOnly(), result.visitsIncluded(), result.title(), result.placesLimited());
    }

    public static List<VisitView> visits(List<VisitResult> results, String token) {
        return results.stream().map(v -> new VisitView((v.origin() == Origin.TRAVEL ? "travel:" : "visit:") + v.id(), v.placeId(),
                v.title(), v.memo(), v.date(), v.time(), v.travelPlanId(), v.travelPlanName(), v.photos().stream().map(p -> photo(p, token)).toList())).toList();
    }

    public static Page<TravelCandidate> candidates(CandidatePage result) {
        return new PageImpl<>(result.content().stream().map(c -> new TravelCandidate(c.id(), c.planId(), c.planName(), c.title(),
                c.date(), c.country(), c.region(), c.latitude(), c.longitude())).toList(),
                PageRequest.of(result.window().page(), result.window().size()), result.total());
    }

    private static PhotoView photo(PhotoData photo, String token) {
        boolean travel = photo.origin() == Origin.TRAVEL;
        String url = travel && token == null ? "/api/travel/media/" + photo.id() + "/content"
                : "/api/record-map/" + (token == null ? "" : "public/" + token + "/") + (travel ? "travel-photos/" : "photos/") + photo.id() + "/content";
        return new PhotoView((travel ? "travel-photo:" : "photo:") + photo.id(), photo.name(), url);
    }
}
