package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelPhotoClusterMember;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelPhotoClusterMemberRepository extends JpaRepository<TravelPhotoClusterMember, Long> {

    List<TravelPhotoClusterMember> findAllByOwnerIdAndClusterIdInOrderByClusterIdAscSortOrderAsc(Long ownerId, Collection<Long> clusterIds);

    void deleteAllByOwnerId(Long ownerId);

    List<TravelPhotoClusterMember> findAllByOwnerIdOrderByClusterIdAscSortOrderAsc(Long ownerId);

    java.util.Optional<TravelPhotoClusterMember> findFirstByOwnerIdAndMediaId(Long ownerId, Long mediaId);

    @org.springframework.data.jpa.repository.Query("""
            select new com.playdata.calen.travel.dto.TravelMyMapPhotoPinResponse(
                asset.id, member.clusterId, record.id, plan.id, plan.name, coalesce(plan.colorHex, '#3182F6'),
                record.expenseDate, record.expenseTime, record.category, record.title, record.country, record.region, record.placeName,
                case when asset.gpsLatitude is not null and asset.gpsLongitude is not null then asset.gpsLatitude else record.latitude end,
                case when asset.gpsLatitude is not null and asset.gpsLongitude is not null then asset.gpsLongitude else record.longitude end,
                concat('/api/travel/media/', cast(asset.id as string), '/content'),
                case when cluster.representativeMediaId = asset.id then true else false end, coalesce(asset.representativeOverride, false))
            from TravelPhotoClusterMember member join TravelMediaAsset asset on asset.id = member.mediaId
            join asset.record record join record.plan plan join TravelPhotoCluster cluster on cluster.id = member.clusterId
            where member.ownerId = :ownerId and cluster.ownerId = :ownerId and plan.owner.id = :ownerId
            order by member.clusterId asc, member.sortOrder asc
            """)
    List<com.playdata.calen.travel.dto.TravelMyMapPhotoPinResponse> findMapPins(@org.springframework.data.repository.query.Param("ownerId") Long ownerId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from TravelPhotoClusterMember member where member.ownerId = :ownerId and member.clusterId in :clusterIds")
    void deleteByOwnerIdAndClusterIds(@org.springframework.data.repository.query.Param("ownerId") Long ownerId,
            @org.springframework.data.repository.query.Param("clusterIds") Collection<Long> clusterIds);
}
