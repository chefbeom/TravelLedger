package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelRouteSegment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelRouteSegmentRepository extends JpaRepository<TravelRouteSegment, Long> {

    List<TravelRouteSegment> findAllByPlanIdAndPlanOwnerIdOrderByRouteDateDescIdDesc(Long planId, Long ownerId);

    List<TravelRouteSegment> findAllByPlanOwnerIdOrderByRouteDateDescIdDesc(Long ownerId);

    Optional<TravelRouteSegment> findByIdAndPlanOwnerId(Long id, Long ownerId);

    void deleteAllByPlanId(Long planId);
}
