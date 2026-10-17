package com.playdata.calen.travel.adapter.out.persistence;

import com.playdata.calen.travel.adapter.support.TravelClusterPhotoMapper;
import com.playdata.calen.travel.application.port.out.TravelClusterProjectionStore;
import com.playdata.calen.travel.domain.TravelMediaType;
import com.playdata.calen.travel.domain.TravelPhotoCluster;
import com.playdata.calen.travel.domain.TravelPhotoClusterMember;
import com.playdata.calen.travel.domain.TravelRecordType;
import com.playdata.calen.travel.domain.model.TravelPhotoClusterData.*;
import com.playdata.calen.travel.domain.policy.TravelPlanPolicy;
import com.playdata.calen.travel.domain.policy.TravelRecordPolicy;
import com.playdata.calen.travel.repository.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaTravelClusterProjectionAdapter implements TravelClusterProjectionStore {
    private final TravelMediaAssetRepository media;
    private final TravelPhotoClusterRepository clusters;
    private final TravelPhotoClusterMemberRepository members;

    @Override @Transactional(readOnly = true)
    public List<PhotoPoint> loadPhotoPoints(Long owner) {
        return media.findAllByPlanOwnerIdAndMediaTypeOrderByUploadedAtDescIdDesc(owner, TravelMediaType.PHOTO).stream()
                .filter(asset -> TravelRecordPolicy.matches(asset.getRecord().getRecordType(), TravelRecordType.MEMORY))
                .filter(TravelClusterPhotoMapper::hasCoordinates).map(TravelClusterPhotoMapper::point).toList();
    }

    @Override public void save(Long owner, List<PhotoCluster> computed) {
        List<PhotoCluster> desired = computed == null ? List.of() : computed;
        Map<Long, TravelPhotoCluster> existing = clusters.findAllByOwnerIdOrderByMemoryDateDescMemoryTimeDescIdDesc(owner).stream()
                .collect(Collectors.toMap(TravelPhotoCluster::getId, Function.identity()));
        Map<Long, List<Long>> existingMembers = members.findAllByOwnerIdOrderByClusterIdAscSortOrderAsc(owner).stream()
                .collect(Collectors.groupingBy(TravelPhotoClusterMember::getClusterId, Collectors.mapping(TravelPhotoClusterMember::getMediaId, Collectors.toList())));
        Set<Long> desiredIds = desired.stream().map(PhotoCluster::id).collect(Collectors.toSet());
        List<Long> removed = existing.keySet().stream().filter(id -> !desiredIds.contains(id)).toList();
        List<Long> changedMemberships = new ArrayList<>(removed);
        List<TravelPhotoCluster> changedClusters = new ArrayList<>();
        List<TravelPhotoClusterMember> memberEntities = new ArrayList<>();
        for (PhotoCluster cluster : desired) {
            TravelPhotoCluster next = entity(owner, cluster);
            TravelPhotoCluster previous = existing.get(cluster.id());
            if (previous == null) changedClusters.add(next);
            else if (!content(previous).equals(content(next))) {
                BeanUtils.copyProperties(next, previous, "id", "ownerId");
                changedClusters.add(previous);
            }
            List<PhotoPoint> points = cluster.members() == null ? List.of() : cluster.members();
            List<Long> mediaIds = points.stream().map(PhotoPoint::mediaId).toList();
            if (!mediaIds.equals(existingMembers.getOrDefault(cluster.id(), List.of()))) {
                changedMemberships.add(cluster.id());
                for (int index = 0; index < points.size(); index++) {
                    var member = new TravelPhotoClusterMember();
                    member.setOwnerId(owner); member.setClusterId(cluster.id()); member.setMediaId(points.get(index).mediaId()); member.setSortOrder(index);
                    memberEntities.add(member);
                }
            }
        }
        for (int offset = 0; offset < changedMemberships.size(); offset += 256)
            members.deleteByOwnerIdAndClusterIds(owner, changedMemberships.subList(offset, Math.min(offset + 256, changedMemberships.size())));
        for (int offset = 0; offset < removed.size(); offset += 256)
            clusters.deleteByOwnerIdAndClusterIds(owner, removed.subList(offset, Math.min(offset + 256, removed.size())));
        if (!changedClusters.isEmpty()) clusters.saveAll(changedClusters);
        if (!memberEntities.isEmpty()) members.saveAll(memberEntities);
    }

    @Override public Snapshot loadSnapshot(Long owner) {
        List<TravelPhotoCluster> stored = clusters.findAllByOwnerIdOrderByMemoryDateDescMemoryTimeDescIdDesc(owner);
        if (stored.isEmpty()) return null;
        var pins = members.findMapPins(owner).stream().map(p -> new Pin(p.mediaId(), p.clusterId(), p.recordId(), p.planId(), p.planName(),
                TravelPlanPolicy.color(p.planColorHex()), p.memoryDate(), p.memoryTime(), p.category(), p.title(), p.country(), p.region(), p.placeName(),
                p.latitude(), p.longitude(), p.representative(), p.representativeOverride())).toList();
        int count = stored.stream().map(TravelPhotoCluster::getPhotoCount).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        return new Snapshot(count, stored.size(), stored.stream().map(this::summary).toList(), pins);
    }

    private Summary summary(TravelPhotoCluster c) {
        return new Summary(c.getId(), c.getRepresentativeMediaId(), c.getRepresentativeRecordId(), c.getPlanId(), c.getPlanName(), c.getPlanColorHex(),
                c.getMemoryDate(), c.getMemoryTime(), c.getCategory(), c.getTitle(), c.getCountry(), c.getRegion(), c.getPlaceName(), c.getLatitude(), c.getLongitude(),
                c.getPhotoCount() != null ? c.getPhotoCount() : 0, c.getMemoryCount() != null ? c.getMemoryCount() : 0,
                c.getMaxDistanceMeters(), Boolean.TRUE.equals(c.getRepresentativeOverride()));
    }

    private List<Object> content(TravelPhotoCluster c) {
        return Arrays.asList(c.getRepresentativeMediaId(), c.getRepresentativeRecordId(), c.getPlanId(), c.getPlanName(), c.getPlanColorHex(),
                c.getMemoryDate(), c.getMemoryTime(), c.getCategory(), c.getTitle(), c.getCountry(), c.getRegion(), c.getPlaceName(),
                decimal(c.getLatitude()), decimal(c.getLongitude()), c.getPhotoCount(), c.getMemoryCount(), decimal(c.getMaxDistanceMeters()), c.getRepresentativeOverride());
    }
    private BigDecimal decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros(); }
    private TravelPhotoCluster entity(Long owner, PhotoCluster cluster) {
        var p = cluster.representative();
        var entity = new TravelPhotoCluster();
        entity.setId(cluster.id()); entity.setOwnerId(owner);
        entity.setRepresentativeMediaId(p.mediaId()); entity.setRepresentativeRecordId(p.recordId()); entity.setPlanId(p.planId());
        entity.setPlanName(p.planName()); entity.setPlanColorHex(TravelPlanPolicy.color(p.planColorHex())); entity.setMemoryDate(p.memoryDate());
        entity.setMemoryTime(p.memoryTime()); entity.setCategory(p.category()); entity.setTitle(p.title()); entity.setCountry(p.country());
        entity.setRegion(p.region()); entity.setPlaceName(p.placeName()); entity.setLatitude(p.latitude()); entity.setLongitude(p.longitude());
        entity.setPhotoCount(cluster.photoCount()); entity.setMemoryCount(cluster.memoryCount()); entity.setMaxDistanceMeters(cluster.maxDistanceMeters());
        entity.setRepresentativeOverride(p.representativeOverride()); entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
