package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.domain.model.TravelAtlasData.*;
import com.playdata.calen.travel.dto.*;

public final class TravelAtlasMapper {
    private TravelAtlasMapper() {}
    public static TravelPublicTripsOverviewResponse overview(Overview v) {
        return new TravelPublicTripsOverviewResponse(v.plans().size(), v.photos(), v.clusters().size(), v.routes().size(), v.distance(),
                v.plans().stream().map(TravelAtlasMapper::trip).toList(), v.clusters().stream().map(TravelAtlasMapper::summary).toList(),
                v.pins().stream().map(TravelAtlasMapper::pin).toList(), v.routes().stream().map(TravelRouteMapper::response).toList());
    }
    private static TravelPublicTripSummaryResponse trip(Trip v) {
        var p = v.plan(); var s = v.sharing();
        return new TravelPublicTripSummaryResponse(p.id(), p.name(), p.destination(), p.start(), p.end(), p.status().name(), p.color(), p.owner().login(), p.owner().display(),
                s.at(), v.memories(), v.photos(), v.routes(), v.distance(), url(v.hero()), s.visibility(), s.share());
    }
    private static TravelPublicPhotoClusterSummaryResponse summary(Summary v) {
        var p = v.value(); var s = v.sharing();
        return new TravelPublicPhotoClusterSummaryResponse(p.id(), p.representativeMediaId(), p.representativeRecordId(), p.planId(), p.planName(), p.planColorHex(),
                p.memoryDate(), p.memoryTime(), p.category(), p.title(), p.country(), p.region(), p.placeName(), p.latitude(), p.longitude(), p.photoCount(), p.memoryCount(),
                p.maxDistanceMeters(), p.representativeOverride(), url(v.photo()), s.login(), s.display(), s.at(), s.visibility(), s.share());
    }
    private static TravelPublicPhotoPinResponse pin(Pin v) {
        var p = v.value(); var s = v.sharing();
        return new TravelPublicPhotoPinResponse(p.mediaId(), p.clusterId(), p.recordId(), p.planId(), p.planName(), p.planColorHex(), p.memoryDate(), p.memoryTime(),
                p.category(), p.title(), p.country(), p.region(), p.placeName(), p.latitude(), p.longitude(), url(v.photo()), p.representative(), p.representativeOverride(),
                s.login(), s.display(), s.at(), s.visibility(), s.share());
    }
    public static TravelMyMapPhotoClusterPageResponse cluster(Cluster v) {
        var page = v.page(); var c = page.cluster();
        return new TravelMyMapPhotoClusterPageResponse(c.id(), c.representativeMediaId(), c.representativeRecordId(), c.latitude(), c.longitude(), c.photoCount(), c.memoryCount(),
                c.maxDistanceMeters(), c.representativeOverride(), c.representativePhoto() == null ? null : TravelClusterDetailMapper.response(c.representativePhoto(), url(v.references().get(c.representativePhoto().id()))),
                page.photos().stream().map(p -> TravelClusterDetailMapper.response(p, url(v.references().get(p.id())))).toList(), page.page(), page.size(), page.totalPhotoCount(), page.hasNext());
    }
    public static TravelCommunityFeedPageResponse community(Feed v) {
        return new TravelCommunityFeedPageResponse(v.items().stream().map(post -> {
            var m = post.memory().marker(); var hero = post.hero();
            return new TravelCommunityPostResponse(m.id(), m.planId(), m.planName(), post.color(), hero.uploadedBy(), m.title(), post.memory().memo(), m.date(), m.time(),
                    m.country(), m.region(), m.placeName(), m.latitude(), m.longitude(), url(post.photo()), hero.caption(), post.photoCount());
        }).toList(), v.page(), v.size(), v.total(), v.totalPages());
    }
    private static String url(Reference v) {
        if (v == null) return "";
        return v.share() == null ? "/api/travel/public/media/" + v.media() + "/content?token=" + v.token()
                : "/api/travel/shared-exhibits/" + v.share() + "/media/" + v.media() + "/content";
    }
}
