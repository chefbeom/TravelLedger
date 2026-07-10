package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelBudgetItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TravelBudgetItemRepository extends JpaRepository<TravelBudgetItem, Long> {

    List<TravelBudgetItem> findAllByPlanIdAndPlanOwnerIdOrderByDisplayOrderAscIdAsc(Long planId, Long ownerId);

    @Query("""
            select coalesce(sum(item.amountKrw), 0)
            from TravelBudgetItem item
            where item.plan.id = :planId
            """)
    BigDecimal sumAmountKrwByPlanId(@Param("planId") Long planId);

    @Query("""
            select item.plan.id as planId,
                   coalesce(sum(item.amountKrw), 0) as totalAmountKrw,
                   count(item) as itemCount
            from TravelBudgetItem item
            where item.plan.owner.id = :ownerId
            group by item.plan.id
            """)
    List<PlanBudgetAggregate> summarizeByPlanOwnerId(@Param("ownerId") Long ownerId);

    List<TravelBudgetItem> findAllByPlanOwnerId(Long ownerId);

    Optional<TravelBudgetItem> findByIdAndPlanOwnerId(Long id, Long ownerId);

    void deleteAllByPlanId(Long planId);

    interface PlanBudgetAggregate {
        Long getPlanId();

        BigDecimal getTotalAmountKrw();

        Long getItemCount();
    }
}
