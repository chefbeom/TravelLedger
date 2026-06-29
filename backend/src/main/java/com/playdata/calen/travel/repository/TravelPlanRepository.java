package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TravelPlanRepository extends JpaRepository<TravelPlan, Long> {

    long countByStatus(TravelPlanStatus status);

    List<TravelPlan> findAllByOwnerIdOrderByStartDateDescIdDesc(Long ownerId);

    List<TravelPlan> findAllByPublicSharedTrueOrderByPublicSharedAtDescStartDateDescIdDesc();

    List<TravelPlan> findAllByPublicSharedTrueAndStatusOrderByPublicSharedAtDescStartDateDescIdDesc(TravelPlanStatus status);

    Optional<TravelPlan> findByIdAndOwnerId(Long id, Long ownerId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TravelPlan plan
            set plan.publicShared = false,
                plan.publicSharedAt = null
            where plan.owner.id = :ownerId
              and plan.publicShared = true
            """)
    int revokePublicSharingByOwnerId(@Param("ownerId") Long ownerId);
}