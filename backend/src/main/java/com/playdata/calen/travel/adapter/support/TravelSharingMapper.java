package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.domain.model.TravelSharingData.*;
import com.playdata.calen.travel.dto.*;

public final class TravelSharingMapper {
    private TravelSharingMapper() {}

    public static TravelShareRecipientResponse recipient(Recipient value) {
        return new TravelShareRecipientResponse(value.id(), value.loginId(), value.displayName());
    }
    public static TravelShareGroupResponse group(Group value) {
        return new TravelShareGroupResponse(value.id(), value.name(), value.recipients().stream().map(TravelSharingMapper::recipient).toList(),
                value.createdAt(), value.updatedAt());
    }
    public static TravelPlanShareResponse share(Share value) {
        return new TravelPlanShareResponse(value.id(), value.planId(), value.planName(), value.recipientLoginId(), value.recipientDisplayName(), value.sharedAt());
    }
    public static TravelPlanPublicShareResponse publicShare(PublicShare value) {
        return new TravelPlanPublicShareResponse(value.planId(), value.publicShared(), value.publicSharedAt());
    }
}
