package com.playdata.calen.travel.adapter.out.persistence;

import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.adapter.support.TravelRouteMapper;
import com.playdata.calen.travel.application.port.out.*;
import com.playdata.calen.travel.domain.TravelRouteSegment;
import com.playdata.calen.travel.domain.model.TravelRouteData.*;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.service.TravelDriveLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class JpaTravelRouteAdapter implements TravelRouteStore {
    private final TravelPlanRepository plans;
    private final TravelRouteSegmentRepository routes;
    private final TravelRoutePayloadPort payload;
    private final TravelDriveLinkService drive;
    @Override public Plan ownedPlan(Long owner,Long id) {
        var plan=plans.findByIdAndOwnerId(id,owner).orElseThrow(()->new NotFoundException("Travel plan not found."));
        return new Plan(plan.getId(),plan.getName(),plan.getColorHex());
    }
    @Override public Access ownedRoute(Long owner,Long id) {
        var route=routes.findByIdAndPlanOwnerId(id,owner).orElseThrow(()->new NotFoundException("Travel route not found."));
        var plan=route.getPlan();return new Access(route.getId(),new Plan(plan.getId(),plan.getName(),plan.getColorHex()));
    }
    @Override public Long create(Long plan,Value value) {
        var route=new TravelRouteSegment();route.setPlan(plans.getReferenceById(plan));apply(route,value);
        return routes.save(route).getId();
    }
    @Override public void update(Long id,Value value) {apply(routes.getReferenceById(id),value);}
    @Override public Result result(Long id) {return TravelRouteMapper.result(routes.getReferenceById(id),payload,false);}
    @Override public void removeFilesAndLinks(Long id) {
        var route=routes.getReferenceById(id);
        // Preserve the existing individual-route deletion order. Do not silently change file compensation semantics.
        drive.removeRouteGpxLinks(route);payload.deleteFiles(route.getGpxFilesJson());
    }
    @Override public void delete(Long id) {routes.delete(routes.getReferenceById(id));}
    @Override public void replaceFiles(Long id,java.util.List<GpxFile> files) {
        var route=routes.getReferenceById(id);
        route.setSourceType(com.playdata.calen.travel.domain.TravelRouteSourceType.GPX);
        route.setGpxFilesJson(payload.encodeFiles(files));
        drive.replaceRouteGpxLinks(route,files.stream().map(file->new TravelDriveLinkService.TravelLinkedFile(
                file.originalFileName(),file.storagePath(),file.contentType(),file.fileSize())).toList());
    }
    private void apply(TravelRouteSegment route,Value v) {
        route.setRouteDate(v.routeDate());route.setTitle(v.title());route.setTransportMode(v.transportMode());
        route.setDistanceKm(v.distanceKm());route.setDurationMinutes(v.durationMinutes());route.setStepCount(v.stepCount());
        route.setSourceType(v.sourceType());route.setStartPlaceName(v.startPlaceName());route.setEndPlaceName(v.endPlaceName());
        route.setRoutePathJson(v.path());route.setLineColorHex(v.lineColorHex());route.setLineStyle(v.lineStyle());route.setMemo(v.memo());
    }
}
