package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelPlan;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelPlanRepository extends JpaRepository<TravelPlan, Long> {

    List<TravelPlan> findAllByOwnerIdOrderByStartDateDescIdDesc(Long ownerId);

    Optional<TravelPlan> findByIdAndOwnerId(Long id, Long ownerId);
}
