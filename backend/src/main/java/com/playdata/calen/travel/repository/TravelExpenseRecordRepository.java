package com.playdata.calen.travel.repository;

import com.playdata.calen.travel.domain.TravelExpenseRecord;
import com.playdata.calen.travel.domain.TravelMediaType;
import com.playdata.calen.travel.domain.TravelRecordType;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Query(
            value = """
                    select record
                    from TravelExpenseRecord record
                    where record.recordType = :recordType
                      and record.sharedWithCommunity = true
                      and exists (
                        select 1
                        from TravelMediaAsset asset
                        where asset.record = record
                          and asset.mediaType = :mediaType
                      )
                    order by record.expenseDate desc, record.id desc
                    """,
            countQuery = """
                    select count(record)
                    from TravelExpenseRecord record
                    where record.recordType = :recordType
                      and record.sharedWithCommunity = true
                      and exists (
                        select 1
                        from TravelMediaAsset asset
                        where asset.record = record
                          and asset.mediaType = :mediaType
                      )
                    """
    )
    Page<TravelExpenseRecord> findCommunityMemoryPage(
            TravelRecordType recordType,
            TravelMediaType mediaType,
            Pageable pageable
    );

    Optional<TravelExpenseRecord> findByIdAndPlanOwnerIdAndRecordType(Long id, Long ownerId, TravelRecordType recordType);

    void deleteAllByPlanId(Long planId);
}
