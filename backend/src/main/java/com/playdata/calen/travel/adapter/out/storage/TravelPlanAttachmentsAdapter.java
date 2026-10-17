package com.playdata.calen.travel.adapter.out.storage;

import com.playdata.calen.travel.application.port.out.TravelPlanAttachmentsPort;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.TravelDriveLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class TravelPlanAttachmentsAdapter implements TravelPlanAttachmentsPort {
    private final TravelPlanRepository plans;
    private final TravelMediaAssetRepository media;
    private final TravelRouteSegmentRepository routes;
    private final TravelDriveLinkService drive;
    private final TravelAttachmentCleanup cleanup;

    @Override public void removePlanLinks(Long id) { drive.removePlanLinks(plans.getReferenceById(id)); }

    @Override public void removeMedia(Long owner, Long id) {
        cleanup.removeMediaFilesAndLinks(media.findAllByPlanIdAndPlanOwnerIdOrderByUploadedAtDescIdDesc(id, owner));
        media.deleteAllByPlanId(id);
    }

    @Override public void removeRoutes(Long owner, Long id) {
        cleanup.removeRouteFilesAndLinks(routes.findAllByPlanIdAndPlanOwnerIdOrderByRouteDateDescIdDesc(id, owner));
        routes.deleteAllByPlanId(id);
    }
}
