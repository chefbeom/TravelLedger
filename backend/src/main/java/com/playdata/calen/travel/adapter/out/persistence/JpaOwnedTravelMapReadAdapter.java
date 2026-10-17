package com.playdata.calen.travel.adapter.out.persistence;

import static com.playdata.calen.travel.adapter.support.TravelPlanDetailMapper.*;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.adapter.support.TravelClusterDetailMapper;
import com.playdata.calen.travel.application.port.out.TravelOwnedMapReadStore;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.*;
import com.playdata.calen.travel.domain.model.TravelRouteData;
import com.playdata.calen.travel.domain.policy.TravelPlanPolicy;
import com.playdata.calen.travel.repository.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaOwnedTravelMapReadAdapter implements TravelOwnedMapReadStore {
    private final AppUserService users;
    private final TravelPlanRepository plans;
    private final TravelExpenseRecordRepository records;
    private final TravelMediaAssetRepository media;
    private final TravelRouteSegmentRepository routes;

    @Override public void requireActiveOwner(Long owner) { users.getRequiredUser(owner); }
    @Override public int planCount(Long owner) { return plans.findAllByOwnerIdOrderByStartDateDescIdDesc(owner).size(); }
    @Override public List<MarkerSummary> markers(Long owner) {
        var markers = records.findAllByPlanOwnerIdAndRecordType(owner, TravelRecordType.MEMORY).stream()
                .filter(record -> record.getLatitude() != null && record.getLongitude() != null).sorted(RECORD_ORDER).toList();
        var ids = markers.stream().map(TravelExpenseRecord::getId).toList();
        Map<Long, String> photos = ids.isEmpty() ? Map.of() : media.findAllByRecordIdInOrderByUploadedAtDescIdDesc(ids).stream()
                .filter(asset -> asset.getMediaType() == TravelMediaType.PHOTO)
                .collect(Collectors.toMap(asset -> asset.getRecord().getId(), asset -> ownedUrl(asset.getId()), (existing, ignored) -> existing));
        return markers.stream().map(record -> new MarkerSummary(marker(record, false), photos.get(record.getId()))).toList();
    }
    @Override public List<Route> routes(Long owner) {
        return routes.findAllByPlanOwnerIdOrderByRouteDateDescIdDesc(owner).stream().sorted(ROUTE_ORDER).map(route -> {
            var plan = route.getPlan();
            return new Route(route.getId(), new TravelRouteData.Plan(plan.getId(), plan.getName(), plan.getColorHex()),
                    new TravelRouteData.Value(route.getRouteDate(), route.getTitle(), route.getTransportMode(), route.getDistanceKm(), route.getDurationMinutes(),
                            route.getStepCount(), route.getSourceType(), route.getStartPlaceName(), route.getEndPlaceName(), route.getRoutePathJson(),
                            route.getLineColorHex(), route.getLineStyle(), route.getMemo()), route.getGpxFilesJson());
        }).toList();
    }
    @Override public Location selectedMarker(Long owner, Long id) {
        return location(records.findByIdAndPlanOwnerIdAndRecordType(id, owner, TravelRecordType.MEMORY)
                .orElseThrow(() -> new NotFoundException("Travel memory not found.")));
    }
    @Override public List<Location> nearby(Long owner, Location selected, Bounds bounds, int limit) {
        return records.findNearestMemoryCandidates(owner, TravelRecordType.MEMORY, selected.id(), selected.latitude().doubleValue(), selected.longitude().doubleValue(),
                bounds.minLatitude(), bounds.maxLatitude(), bounds.minLongitude(), bounds.maxLongitude(), bounds.allLongitudes(), bounds.crossesDateLine(), PageRequest.of(0, limit))
                .stream().map(this::location).toList();
    }
    @Override public List<MarkerDetail> details(List<Location> markers) {
        var ids = markers.stream().map(Location::id).toList();
        var byRecord = media.findAllByRecordIdInOrderByUploadedAtDescIdDesc(ids).stream().filter(asset -> asset.getMediaType() == TravelMediaType.PHOTO)
                .collect(Collectors.groupingBy(asset -> asset.getRecord().getId()));
        return markers.stream().map(point -> {
            var record = records.getReferenceById(point.id());
            var photos = byRecord.getOrDefault(point.id(), List.of()).stream().sorted(MEDIA_ORDER).map(TravelClusterDetailMapper::media).toList();
            return new MarkerDetail(marker(record, true), record.getSharedWithCommunity(), record.getMemo(), photos);
        }).toList();
    }
    @Override public List<Frame> frames(Long owner) {
        return media.findPhotoFrameMediaByOwnerId(owner, TravelMediaType.PHOTO).stream().map(value -> new Frame(value.getId(), value.getPlanId(), value.getPlanName(),
                value.getRecordType(), value.getOriginalFileName(), value.getCaption(), value.getUploadedAt(), ownedUrl(value.getId()), value.getExpenseDate(), value.getExpenseTime(),
                value.getTitle(), value.getCountry(), value.getRegion(), value.getPlaceName(), value.getLatitude(), value.getLongitude(), value.getGpsLatitude(), value.getGpsLongitude())).toList();
    }
    private Location location(TravelExpenseRecord record) { return new Location(record.getId(), record.getLatitude(), record.getLongitude()); }
    private Marker marker(TravelExpenseRecord record, boolean normalizeColor) {
        var plan = record.getPlan();
        return new Marker(record.getId(), plan.getId(), plan.getName(), normalizeColor ? TravelPlanPolicy.color(plan.getColorHex()) : plan.getColorHex(), record.getExpenseDate(),
                record.getExpenseTime(), record.getCategory(), record.getTitle(), record.getCountry(), record.getRegion(), record.getPlaceName(), record.getLatitude(), record.getLongitude());
    }
    private String ownedUrl(Long id) { return "/api/travel/media/" + id + "/content"; }
}
