package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelPhotoCluster;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelPhotoClusterRepository extends JpaRepository<TravelPhotoCluster, Long> {

    List<TravelPhotoCluster> findAllByOwnerIdOrderByMemoryDateDescMemoryTimeDescIdDesc(Long ownerId);

    java.util.Optional<TravelPhotoCluster> findByIdAndOwnerId(Long id, Long ownerId);

    void deleteAllByOwnerId(Long ownerId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from TravelPhotoCluster cluster where cluster.ownerId = :ownerId and cluster.id in :clusterIds")
    void deleteByOwnerIdAndClusterIds(@org.springframework.data.repository.query.Param("ownerId") Long ownerId,
            @org.springframework.data.repository.query.Param("clusterIds") java.util.Collection<Long> clusterIds);
}
