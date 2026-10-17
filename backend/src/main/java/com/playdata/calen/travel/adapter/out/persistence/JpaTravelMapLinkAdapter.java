package com.playdata.calen.travel.adapter.out.persistence;

import static com.playdata.calen.travel.adapter.support.TravelPlanDetailMapper.*;
import static com.playdata.calen.travel.domain.policy.TravelMapLinkPolicy.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.*;
import com.playdata.calen.travel.adapter.support.*;
import com.playdata.calen.travel.application.port.out.TravelMapLinkStore;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.repository.*;
import com.playdata.calen.travel.domain.model.TravelMapLinkData.*;
import com.playdata.calen.travel.domain.model.TravelOwnedMapData.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.Media;
import com.playdata.calen.travel.domain.model.TravelMediaData.Download;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.PhotoPoint;
import com.playdata.calen.travel.domain.model.TravelRouteData;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaTravelMapLinkAdapter implements TravelMapLinkStore {
    private final AppUserService users;
    private final AppUserRepository accounts;
    private final TravelMapShareLinkRepository links;
    private final TravelPlanRepository plans;
    private final TravelExpenseRecordRepository records;
    private final TravelMediaAssetRepository media;
    private final TravelRouteSegmentRepository routes;
    private final ObjectMapper json;
    @Override public void requireActiveOwner(Long owner) { users.getRequiredUser(owner); }
    @Override public Set<Long> ownedPlans(Long owner, Collection<Long> ids) {
        return plans.findAllById(ids).stream().filter(plan -> plan.getOwner() != null && owner.equals(plan.getOwner().getId()))
                .map(TravelPlan::getId).collect(Collectors.toSet());
    }
    private boolean owned(Long owner, Set<Long> selected, TravelPlan plan) {
        return plan != null && plan.getOwner() != null && owner.equals(plan.getOwner().getId()) && selected.contains(plan.getId());
    }
    @Override public Set<Long> ownedRecords(Long owner, Set<Long> selected, Set<Long> ids) {
        return records.findAllById(ids).stream().filter(record -> owned(owner, selected, record.getPlan())).map(TravelExpenseRecord::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    }
    @Override public Set<Long> ownedMedia(Long owner, Set<Long> selected, Set<Long> ids) {
        return media.findAllById(ids).stream().filter(asset -> owned(owner, selected, asset.getPlan())).map(TravelMediaAsset::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    }
    @Override public Set<Long> ownedRoutes(Long owner, Set<Long> selected, Set<Long> ids) {
        return routes.findAllById(ids).stream().filter(route -> owned(owner, selected, route.getPlan())).map(TravelRouteSegment::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    }
    @Override public Link save(Long owner, String token, String title, Scope scope, LocalDateTime now) {
        var link = new TravelMapShareLink(); link.setOwner(accounts.getReferenceById(owner)); link.setToken(token); link.setTitle(title);
        link.setPlanIdsJson(encode(scope.plans())); link.setExcludedRecordIdsJson(encode(scope.records()));
        link.setExcludedMediaIdsJson(encode(scope.media())); link.setExcludedRouteIdsJson(encode(scope.routes()));
        link.setActive(true); link.setCreatedAt(now); link.setUpdatedAt(now);
        return value(links.save(link));
    }
    @Override public Link active(String token) {
        return value(links.findByTokenAndActiveTrue(token).orElseThrow(() -> new NotFoundException("Shared travel map not found.")));
    }
    private Link value(TravelMapShareLink link) {
        var scope = new Scope(decode(link.getPlanIdsJson()), new LinkedHashSet<>(decode(link.getExcludedRecordIdsJson())),
                new LinkedHashSet<>(decode(link.getExcludedMediaIdsJson())), new LinkedHashSet<>(decode(link.getExcludedRouteIdsJson())));
        var owner = link.getOwner();
        return new Link(owner.getId(), owner.getLoginId(), owner.getDisplayName(), link.getToken(), link.getTitle(), scope, link.getCreatedAt(), link.getUpdatedAt());
    }
    private String encode(Collection<Long> values) {
        try { return json.writeValueAsString(ids(values)); }
        catch (JsonProcessingException e) { throw new BadRequestException("Failed to save shared travel map scope."); }
    }
    private List<Long> decode(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return ids(json.readValue(value, new TypeReference<List<Long>>() {})); }
        catch (JsonProcessingException e) { return List.of(); }
    }
    @Override public List<Marker> markers(List<Long> selected) {
        return records.findAllByPlanIdInAndRecordType(selected, TravelRecordType.MEMORY).stream().sorted(RECORD_ORDER).map(record -> {
            var plan = record.getPlan();
            return new Marker(record.getId(), plan.getId(), plan.getName(), plan.getColorHex(), record.getExpenseDate(), record.getExpenseTime(), record.getCategory(),
                    record.getTitle(), record.getCountry(), record.getRegion(), record.getPlaceName(), record.getLatitude(), record.getLongitude());
        }).toList();
    }
    @Override public List<Asset> markerPhotos(List<Long> ids) {
        return media.findAllByRecordIdInOrderByUploadedAtDescIdDesc(ids).stream().map(this::access).toList();
    }
    @Override public List<Route> routes(List<Long> selected) {
        return routes.findAllByPlanIdInOrderByRouteDateDescIdDesc(selected).stream().sorted(ROUTE_ORDER).map(route -> {
            var plan = route.getPlan();
            return new Route(route.getId(), new TravelRouteData.Plan(plan.getId(), plan.getName(), plan.getColorHex()),
                    new TravelRouteData.Value(route.getRouteDate(), route.getTitle(), route.getTransportMode(), route.getDistanceKm(), route.getDurationMinutes(),
                            route.getStepCount(), route.getSourceType(), route.getStartPlaceName(), route.getEndPlaceName(), route.getRoutePathJson(),
                            route.getLineColorHex(), route.getLineStyle(), route.getMemo()), route.getGpxFilesJson());
        }).toList();
    }
    @Override public List<PhotoPoint> photos(Set<Long> selected, Scope scope) {
        return media.findAllByPlanIdInAndMediaTypeOrderByUploadedAtDescIdDesc(selected, TravelMediaType.PHOTO).stream()
                .filter(asset -> visible(access(asset), selected, scope)).filter(TravelClusterPhotoMapper::hasCoordinates).map(TravelClusterPhotoMapper::point).toList();
    }
    @Override public List<Media> details(List<Long> ids) {
        return ids.stream().map(id -> TravelClusterDetailMapper.media(media.getReferenceById(id))).toList();
    }
    @Override public Asset asset(Long id) {
        return access(media.findById(id).orElseThrow(() -> new NotFoundException("Shared travel media not found.")));
    }
    private Asset access(TravelMediaAsset asset) {
        if (asset == null || asset.getPlan() == null || asset.getRecord() == null) return null;
        var record = asset.getRecord();
        return new Asset(asset.getId(), asset.getPlan().getId(), record.getId(), record.getRecordType(), asset.getMediaType(),
                record.getLatitude() != null && record.getLongitude() != null, TravelClusterPhotoMapper.hasCoordinates(asset),
                new Download(asset.getStoragePath(), asset.getContentType(), asset.getOriginalFileName()));
    }
}
