package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelBudgetItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelBudgetItemRepository extends JpaRepository<TravelBudgetItem, Long> {

    List<TravelBudgetItem> findAllByPlanIdAndPlanOwnerIdOrderByDisplayOrderAscIdAsc(Long planId, Long ownerId);

    List<TravelBudgetItem> findAllByPlanOwnerId(Long ownerId);

    Optional<TravelBudgetItem> findByIdAndPlanOwnerId(Long id, Long ownerId);

    void deleteAllByPlanId(Long planId);
}
