package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelPlanShare;
import com.playdata.calen.travel.domain.TravelPlanStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface TravelPlanShareRepository extends JpaRepository<TravelPlanShare, Long> {

    Optional<TravelPlanShare> findByPlanIdAndRecipientId(Long planId, Long recipientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select share from TravelPlanShare share where share.plan.id = :plan and share.recipient.id = :recipient")
    Optional<TravelPlanShare> findForUpdate(@Param("plan") Long planId, @Param("recipient") Long recipientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select share from TravelPlanShare share where share.id = :id and share.plan.id = :plan and share.sharedBy.id = :owner")
    Optional<TravelPlanShare> findOwnedForUpdate(@Param("id") Long id, @Param("plan") Long planId, @Param("owner") Long ownerId);

    List<TravelPlanShare> findAllByRecipientIdOrderByCreatedAtDescIdDesc(Long recipientId);

    Page<TravelPlanShare> findAllByRecipientIdOrderByCreatedAtDescIdDesc(Long recipientId, Pageable pageable);

    List<TravelPlanShare> findAllByRecipientIdAndPlan_StatusOrderByCreatedAtDescIdDesc(
            Long recipientId,
            TravelPlanStatus status
    );

    Page<TravelPlanShare> findAllByRecipientIdAndPlan_StatusOrderByCreatedAtDescIdDesc(
            Long recipientId,
            TravelPlanStatus status,
            Pageable pageable
    );

    List<TravelPlanShare> findAllByPlanIdAndSharedByIdOrderByCreatedAtDescIdDesc(Long planId, Long sharedById);

    Optional<TravelPlanShare> findByIdAndRecipientId(Long id, Long recipientId);

    Optional<TravelPlanShare> findByIdAndPlanIdAndSharedById(Long id, Long planId, Long sharedById);

    void deleteAllByPlanId(Long planId);
}
