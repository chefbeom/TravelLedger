package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelPhotoClusterMember;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelPhotoClusterMemberRepository extends JpaRepository<TravelPhotoClusterMember, Long> {

    List<TravelPhotoClusterMember> findAllByOwnerIdAndClusterIdInOrderByClusterIdAscSortOrderAsc(Long ownerId, Collection<Long> clusterIds);

    void deleteAllByOwnerId(Long ownerId);
}
