package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelPhotoCluster;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelPhotoClusterRepository extends JpaRepository<TravelPhotoCluster, Long> {

    List<TravelPhotoCluster> findAllByOwnerIdOrderByMemoryDateDescMemoryTimeDescIdDesc(Long ownerId);

    void deleteAllByOwnerId(Long ownerId);
}
