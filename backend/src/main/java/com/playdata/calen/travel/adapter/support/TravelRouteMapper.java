package com.playdata.calen.travel.adapter.support;

import com.playdata.calen.travel.application.port.out.TravelRoutePayloadPort;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelRouteData.*;
import com.playdata.calen.travel.domain.policy.*;
import com.playdata.calen.travel.dto.*;
import java.math.*;
import java.util.List;

public final class TravelRouteMapper {
    private TravelRouteMapper() {}
    public static Draft draft(TravelRouteSegmentRequest r) {
        return new Draft(r.routeDate(),r.title(),r.transportMode(),r.distanceKm(),r.durationMinutes(),r.stepCount(),
                r.sourceType(),r.startPlaceName(),r.endPlaceName(),r.lineColorHex(),r.lineStyle(),r.memo(),
                r.points()==null?null:r.points().stream().map(p->p==null?null:new Point(p.latitude(),p.longitude(),p.pointType(),p.linkedMemoryId(),p.label())).toList());
    }
    public static TravelRouteSegmentResponse response(Result r) {
        return new TravelRouteSegmentResponse(r.id(),r.plan().id(),r.plan().name(),r.plan().colorHex(),r.routeDate(),
                r.title(),r.transportMode(),r.distanceKm(),r.durationMinutes(),r.stepCount(),r.sourceType(),
                r.startPlaceName(),r.endPlaceName(),r.lineColorHex(),r.lineStyle(),r.gpxFileNames(),r.memo(),
                points(r.points()));
    }
    public static List<TravelRoutePointResponse> points(List<Point> values) {
        return values.stream().map(p->new TravelRoutePointResponse(p.latitude(),p.longitude(),p.pointType(),p.linkedMemoryId(),p.label())).toList();
    }
    /** Snapshot map reads intentionally avoid opening original GPX files, unlike full plan details. */
    public static Result result(TravelRouteSegment route,TravelRoutePayloadPort payload,boolean snapshotOnly) {
        var plan=route.getPlan();
        var files=payload.files(route.getGpxFilesJson()).stream().map(GpxFile::originalFileName);
        var names=snapshotOnly?files.toList():files.filter(name->name!=null&&!name.isBlank()).toList();
        Integer duration=route.getDurationMinutes();
        if(!snapshotOnly && duration==null) duration=0;
        return new Result(route.getId(),new Plan(plan.getId(),plan.getName(),TravelPlanPolicy.color(plan.getColorHex())),
                route.getRouteDate(),route.getTitle(),route.getTransportMode(),
                route.getDistanceKm()!=null?route.getDistanceKm().setScale(3,RoundingMode.HALF_UP):BigDecimal.ZERO.setScale(3,RoundingMode.HALF_UP),
                duration,
                route.getStepCount(),route.getSourceType(),route.getStartPlaceName(),route.getEndPlaceName(),
                snapshotOnly?TravelPlanPolicy.color(route.getLineColorHex()):TravelRoutePolicy.lineColor(route.getLineColorHex(),plan.getColorHex()),
                route.getLineStyle()!=null?route.getLineStyle():TravelRouteLineStyle.SOLID,names,route.getMemo(),
                snapshotOnly?payload.decode(route.getRoutePathJson()):payload.resolve(route.getSourceType(),route.getGpxFilesJson(),route.getRoutePathJson()));
    }
}
