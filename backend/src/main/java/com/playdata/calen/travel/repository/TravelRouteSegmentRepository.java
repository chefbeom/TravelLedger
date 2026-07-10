package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelRouteSegment;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TravelRouteSegmentRepository extends JpaRepository<TravelRouteSegment, Long> {

    @EntityGraph(attributePaths = "plan")
    List<TravelRouteSegment> findAllByPlanIdAndPlanOwnerIdOrderByRouteDateDescIdDesc(Long planId, Long ownerId);

    @EntityGraph(attributePaths = "plan")
    List<TravelRouteSegment> findAllByPlanOwnerIdOrderByRouteDateDescIdDesc(Long ownerId);

    List<TravelRouteSegment> findAllByPlanPublicSharedTrueOrderByRouteDateDescIdDesc();

    List<TravelRouteSegment> findAllByPlanIdInOrderByRouteDateDescIdDesc(Collection<Long> planIds);

    @Query("""
            select route.plan.id as planId,
                   count(route) as routeSegmentCount,
                   coalesce(sum(route.distanceKm), 0) as totalDistanceKm,
                   coalesce(sum(route.durationMinutes), 0) as totalDurationMinutes,
                   coalesce(sum(route.stepCount), 0) as totalStepCount
            from TravelRouteSegment route
            where route.plan.owner.id = :ownerId
            group by route.plan.id
            """)
    List<PlanRouteAggregate> summarizeByPlanOwnerId(@Param("ownerId") Long ownerId);

    Optional<TravelRouteSegment> findByIdAndPlanOwnerId(Long id, Long ownerId);

    void deleteAllByPlanId(Long planId);

    interface PlanRouteAggregate {
        Long getPlanId();

        Long getRouteSegmentCount();

        BigDecimal getTotalDistanceKm();

        Long getTotalDurationMinutes();

        Long getTotalStepCount();
    }
}
