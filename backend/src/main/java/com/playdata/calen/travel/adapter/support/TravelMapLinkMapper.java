package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.domain.model.TravelMapLinkData.*;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData;
import com.playdata.calen.travel.dto.*;
import java.util.List;

public final class TravelMapLinkMapper {
    private TravelMapLinkMapper() {}
    public static Draft draft(TravelMapShareLinkRequest r) { return new Draft(r.title(), r.planIds(), r.excludedRecordIds(), r.excludedMediaIds(), r.excludedRouteIds()); }
    public static TravelMapShareLinkResponse link(Link v) {
        return new TravelMapShareLinkResponse(v.token(), v.title(), v.scope().plans(), List.copyOf(v.scope().records()), List.copyOf(v.scope().media()),
                List.copyOf(v.scope().routes()), v.createdAt(), v.updatedAt());
    }
    public static TravelPublicMapShareResponse map(PublicMap v) {
        var link = v.link(); var value = v.overview(); var token = link.token();
        var overview = new TravelOwnedMapData.Overview(value.plans(), value.markers().size(), value.photoCount(), value.clusters().size(), value.routes().size(), value.distance(),
                value.markers().stream().map(m -> new TravelOwnedMapData.MarkerSummary(m.marker(), m.photo() == null ? null : url(token, m.photo()))).toList(),
                value.clusters().stream().map(c -> new TravelOwnedMapData.Cluster(c, url(token, c.representativeMediaId()))).toList(),
                value.pins().stream().map(p -> new TravelOwnedMapData.Pin(p, url(token, p.mediaId()))).toList(), value.routes());
        return new TravelPublicMapShareResponse(token, link.title(), link.ownerLogin(), link.ownerDisplay(), link.scope().plans(), link.createdAt(), TravelOwnedMapMapper.response(overview));
    }
    public static TravelMyMapPhotoClusterPageResponse cluster(Cluster value) {
        var v = value.page(); var token = value.token();
        var c = v.cluster();
        return new TravelMyMapPhotoClusterPageResponse(c.id(), c.representativeMediaId(), c.representativeRecordId(), c.latitude(), c.longitude(),
                c.photoCount(), c.memoryCount(), c.maxDistanceMeters(), c.representativeOverride(),
                c.representativePhoto() == null ? null : TravelClusterDetailMapper.response(c.representativePhoto(), url(token, c.representativePhoto().id())),
                v.photos().stream().map(p -> TravelClusterDetailMapper.response(p, url(token, p.id()))).toList(), v.page(), v.size(), v.totalPhotoCount(), v.hasNext());
    }
    public static TravelLoginMapPreviewResponse preview(Preview v) {
        return new TravelLoginMapPreviewResponse(v.enabled(), "여행 기록 미리보기", v.markers().size(), v.routes().size(),
                v.markers().stream().map(m -> new TravelLoginMapPreviewMarkerResponse(m.number(), m.latitude(), m.longitude(),
                        m.photo() ? "/api/travel/public/login-map-preview/markers/" + m.number() + "/thumbnail" : null)).toList(),
                v.routes().stream().map(r -> new TravelLoginMapPreviewRouteResponse(r.number(), r.points().stream()
                        .map(p -> new TravelLoginMapPreviewPointResponse(p.latitude(), p.longitude())).toList())).toList());
    }
    private static String url(String token, Long media) { return "/api/travel/public/map-shares/" + token + "/media/" + media + "/content"; }
}
