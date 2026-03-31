package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelRecordType;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TravelExpenseRecordRepository extends JpaRepository<TravelExpenseRecord, Long> {

    @Query("""
            select coalesce(sum(record.amountKrw), 0)
            from TravelExpenseRecord record
            """)
    BigDecimal sumAmountKrw();

    List<TravelExpenseRecord> findAllByPlanIdAndPlanOwnerIdOrderByExpenseDateDescIdDesc(Long planId, Long ownerId);

    List<TravelExpenseRecord> findAllByPlanOwnerId(Long ownerId);

    Optional<TravelExpenseRecord> findByIdAndPlanOwnerId(Long id, Long ownerId);

    List<TravelExpenseRecord> findAllByPlanIdAndPlanOwnerIdAndRecordTypeOrderByExpenseDateDescIdDesc(
            Long planId,
            Long ownerId,
            TravelRecordType recordType
    );

    List<TravelExpenseRecord> findAllByPlanOwnerIdAndRecordType(Long ownerId, TravelRecordType recordType);

    List<TravelExpenseRecord> findAllByRecordTypeAndSharedWithCommunityTrueOrderByExpenseDateDescIdDesc(TravelRecordType recordType);

    Optional<TravelExpenseRecord> findByIdAndPlanOwnerIdAndRecordType(Long id, Long ownerId, TravelRecordType recordType);

    void deleteAllByPlanId(Long planId);
}
