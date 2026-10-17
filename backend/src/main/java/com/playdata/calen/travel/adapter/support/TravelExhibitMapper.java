package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.domain.model.TravelExhibitData.*;
import com.playdata.calen.travel.dto.*;

public final class TravelExhibitMapper {
    private TravelExhibitMapper() {}
    public static TravelSharedExhibitPageResponse page(Page page) {
        return new TravelSharedExhibitPageResponse(page.items().stream().map(value -> new TravelSharedExhibitSummaryResponse(value.id(), value.planId(), value.planName(),
                value.destination(), value.startDate(), value.endDate(), value.status().name(), value.colorHex(), value.sharedBy().loginId(), value.sharedBy().displayName(),
                value.sharedBy().sharedAt(), value.memoryRecordCount(), value.routeSegmentCount(), value.mediaItemCount())).toList(), page.page(), page.size(), page.totalElements(), page.totalPages());
    }
    public static TravelSharedExhibitDetailResponse received(Detail detail) { return detail(detail, "shared-exhibits"); }
    public static TravelSharedExhibitDetailResponse recordShare(Detail detail) { return detail(detail, "record-shares"); }
    private static TravelSharedExhibitDetailResponse detail(Detail detail, String surface) {
        return new TravelSharedExhibitDetailResponse(detail.id(), detail.sharedBy().loginId(), detail.sharedBy().displayName(), detail.sharedBy().sharedAt(),
                TravelPlanDetailMapper.response(detail.plan(), id -> "/api/travel/" + surface + "/" + detail.id() + "/media/" + id + "/content"));
    }
}
