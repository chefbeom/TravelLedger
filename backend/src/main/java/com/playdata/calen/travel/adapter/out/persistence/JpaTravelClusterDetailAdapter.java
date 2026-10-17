package com.playdata.calen.travel.adapter.out.persistence;

import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.travel.adapter.support.TravelClusterDetailMapper;
import com.playdata.calen.travel.application.port.out.TravelClusterDetailStore;
import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelClusterDetailData.*;
import com.playdata.calen.travel.repository.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JpaTravelClusterDetailAdapter implements TravelClusterDetailStore {
    private final AppUserService users;
    private final TravelPhotoClusterRepository clusters;
    private final TravelPhotoClusterMemberRepository members;
    private final TravelMediaAssetRepository media;

    @Override public void requireOwner(Long owner) { users.getRequiredUser(owner); }

    @Override public Page page(Long owner, Long clusterId, int page, int size, Long focus) {
        var cluster = clusters.findByIdAndOwnerId(clusterId, owner)
                .orElseThrow(() -> new NotFoundException("Photo cluster not found."));
        var photos = media.findClusterPhotoPage(owner, clusterId, focus, cluster.getRepresentativeMediaId(), PageRequest.of(page, size));
        var representative = ownedMedia(owner, List.of(cluster.getRepresentativeMediaId()));
        var header = detail(cluster, List.of(), representative);
        return new Page(header, photos.getContent().stream().map(TravelClusterDetailMapper::media).toList(),
                page, size, Math.toIntExact(photos.getTotalElements()), photos.hasNext());
    }

    @Override public Optional<Detail> detail(Long owner, Long clusterId) {
        return clusters.findByIdAndOwnerId(clusterId, owner).map(cluster -> {
            var membership = members.findAllByOwnerIdAndClusterIdInOrderByClusterIdAscSortOrderAsc(owner, List.of(clusterId));
            Set<Long> ids = membership.stream().map(TravelPhotoClusterMember::getMediaId).collect(Collectors.toSet());
            ids.add(cluster.getRepresentativeMediaId());
            return detail(cluster, membership, ownedMedia(owner, ids));
        });
    }

    @Override public void changeRepresentative(List<Long> memberIds, Long selected) {
        var assets = media.findAllById(memberIds).stream().collect(Collectors.toMap(TravelMediaAsset::getId, Function.identity()));
        memberIds.forEach(id -> {
            var asset = assets.get(id);
            if (asset != null) asset.setRepresentativeOverride(id.equals(selected));
        });
        media.saveAll(memberIds.stream().map(assets::get).filter(Objects::nonNull).toList());
    }

    @Override public Optional<Long> clusterForMedia(Long owner, Long mediaId) {
        return members.findFirstByOwnerIdAndMediaId(owner, mediaId).map(TravelPhotoClusterMember::getClusterId);
    }

    private Map<Long, TravelMediaAsset> ownedMedia(Long owner, Collection<Long> ids) {
        return media.findAllByIdIn(ids).stream().filter(asset -> owner.equals(asset.getPlan().getOwner().getId()))
                .collect(Collectors.toMap(TravelMediaAsset::getId, Function.identity()));
    }

    private Detail detail(TravelPhotoCluster cluster, List<TravelPhotoClusterMember> membership, Map<Long, TravelMediaAsset> assets) {
        var photos = membership.stream().map(TravelPhotoClusterMember::getMediaId).map(assets::get).filter(Objects::nonNull)
                .map(TravelClusterDetailMapper::media).toList();
        var representative = photos.stream().filter(photo -> photo.id() != null && photo.id().equals(cluster.getRepresentativeMediaId()))
                .findFirst().orElseGet(() -> {
                    var asset = assets.get(cluster.getRepresentativeMediaId());
                    return asset == null ? null : TravelClusterDetailMapper.media(asset);
                });
        return new Detail(cluster.getId(), representative != null ? representative.id() : cluster.getRepresentativeMediaId(),
                cluster.getRepresentativeRecordId(), cluster.getLatitude(), cluster.getLongitude(),
                cluster.getPhotoCount() != null ? cluster.getPhotoCount() : photos.size(),
                cluster.getMemoryCount() != null ? cluster.getMemoryCount() : 0, cluster.getMaxDistanceMeters(),
                Boolean.TRUE.equals(cluster.getRepresentativeOverride()), representative, photos);
    }
}
