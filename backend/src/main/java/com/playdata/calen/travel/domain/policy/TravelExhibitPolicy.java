package com.playdata.calen.travel.domain.policy;

import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelExhibitData.PublicMedia;

public final class TravelExhibitPolicy {
    private TravelExhibitPolicy() {}
    public static int page(Integer page) { return page == null ? 0 : Math.max(0, page); }
    public static int size(Integer size) { return size == null ? 10 : Math.max(1, Math.min(size, 20)); }
    public static boolean completed(TravelPlanStatus status) { return TravelPlanPolicy.status(status) == TravelPlanStatus.COMPLETED; }
    public static boolean memoryPhoto(TravelRecordType record, TravelMediaType media) {
        return record == TravelRecordType.MEMORY && media == TravelMediaType.PHOTO;
    }
    public static boolean publicMedia(PublicMedia media) {
        return memoryPhoto(media.recordType(), media.mediaType())
                && (media.community() || (media.publicPlan() && completed(media.status())));
    }
}
