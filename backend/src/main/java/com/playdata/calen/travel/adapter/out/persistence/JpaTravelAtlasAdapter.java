package com.playdata.calen.travel.adapter.out.persistence;

import static com.playdata.calen.travel.adapter.support.TravelPlanDetailMapper.*;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.travel.adapter.support.*;
import com.playdata.calen.travel.application.port.out.TravelAtlasStore;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelAtlasData.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.Media;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.Route;
import com.playdata.calen.travel.domain.model.TravelRouteData;
import com.playdata.calen.travel.domain.policy.TravelRecordPolicy;
import com.playdata.calen.travel.repository.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaTravelAtlasAdapter implements TravelAtlasStore {
    private final AppUserService users;
    private final TravelPlanRepository plans;
    private final TravelPlanShareRepository shares;
    private final TravelExpenseRecordRepository records;
    private final TravelMediaAssetRepository media;
    private final TravelRouteSegmentRepository routes;
    @Override public void requireActiveViewer(Long viewer) { users.getRequiredUser(viewer); }
    @Override public List<Plan> publishedCompletedPlans() {
        return plans.findAllByPublicSharedTrueAndStatusOrderByPublicSharedAtDescStartDateDescIdDesc(TravelPlanStatus.COMPLETED).stream().map(this::plan).toList();
    }
    @Override public List<Access> receivedCompletedPlans(Long viewer) {
        return shares.findAllByRecipientIdAndPlan_StatusOrderByCreatedAtDescIdDesc(viewer, TravelPlanStatus.COMPLETED).stream()
                .map(share -> new Access(plan(share.getPlan()), share.getId(), share.getCreatedAt())).toList();
    }
    @Override public List<Memory> memories(List<Long> ids) {
        return records.findAllByPlanIdInAndRecordType(ids, TravelRecordType.MEMORY).stream().sorted(RECORD_ORDER).map(this::memory).toList();
    }
    @Override public List<Photo> photos(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return media.findAllByPlanIdInAndMediaTypeOrderByUploadedAtDescIdDesc(ids, TravelMediaType.PHOTO).stream()
                .filter(asset -> TravelRecordPolicy.matches(asset.getRecord().getRecordType(), TravelRecordType.MEMORY))
                .filter(TravelClusterPhotoMapper::hasCoordinates)
                .map(asset -> new Photo(asset.getId(), plan(asset.getPlan()), TravelClusterPhotoMapper.point(asset))).toList();
    }
    @Override public List<Route> routes(List<Long> ids) {
        return routes.findAllByPlanIdInOrderByRouteDateDescIdDesc(ids).stream().sorted(ROUTE_ORDER).map(route -> {
            var plan = route.getPlan();
            return new Route(route.getId(), new TravelRouteData.Plan(plan.getId(), plan.getName(), plan.getColorHex()),
                    new TravelRouteData.Value(route.getRouteDate(), route.getTitle(), route.getTransportMode(), route.getDistanceKm(), route.getDurationMinutes(),
                            route.getStepCount(), route.getSourceType(), route.getStartPlaceName(), route.getEndPlaceName(), route.getRoutePathJson(),
                            route.getLineColorHex(), route.getLineStyle(), route.getMemo()), route.getGpxFilesJson());
        }).toList();
    }
    @Override public Media detail(Long id) { return TravelClusterDetailMapper.media(media.getReferenceById(id)); }
    @Override public Window community(int page, int size) {
        var rows = records.findCommunityMemoryPage(TravelRecordType.MEMORY, TravelMediaType.PHOTO, PageRequest.of(page, size));
        return new Window(rows.getContent().stream().sorted(RECORD_ORDER).map(this::memory).toList(), rows.getTotalElements(), rows.getTotalPages());
    }
    @Override public List<FeedPhoto> communityPhotos(List<Long> ids) {
        return media.findAllByRecordIdInOrderByUploadedAtDescIdDesc(ids).stream().filter(asset -> asset.getMediaType() == TravelMediaType.PHOTO)
                .map(asset -> new FeedPhoto(asset.getId(), asset.getRecord().getId(), asset.getUploadedAt())).toList();
    }
    @Override public Hero hero(Long id) {
        var asset = media.getReferenceById(id);
        return new Hero(asset.getUploadedBy().getDisplayName(), asset.getCaption());
    }
    private Plan plan(TravelPlan p) {
        var owner = p.getOwner();
        return new Plan(p.getId(), p.getName(), p.getDestination(), p.getStartDate(), p.getEndDate(), p.getStatus(), p.getColorHex(),
                new Owner(owner.getLoginId(), owner.getDisplayName()), p.getPublicShared(), p.getPublicSharedAt());
    }
    private Memory memory(TravelExpenseRecord r) {
        var p = r.getPlan();
        var marker = new TravelOwnedMapData.Marker(r.getId(), p.getId(), p.getName(), p.getColorHex(), r.getExpenseDate(), r.getExpenseTime(),
                r.getCategory(), r.getTitle(), r.getCountry(), r.getRegion(), r.getPlaceName(), r.getLatitude(), r.getLongitude());
        return new Memory(marker, r.getRecordType(), r.getMemo());
    }
}
