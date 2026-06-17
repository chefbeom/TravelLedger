package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.LedgerEntry;
import com.playdata.calen.ledger.domain.PaymentMethodKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    long countByDeletedAtIsNull();

    long countByDeletedAtIsNotNull();

    List<LedgerEntry> findAllByOwnerIdAndDeletedAtIsNullOrderByEntryDateAscIdAsc(Long ownerId);

    List<LedgerEntry> findAllByOwnerIdAndDeletedAtIsNullAndEntryDateBetweenOrderByEntryDateAscIdAsc(Long ownerId, LocalDate from, LocalDate to);

    List<LedgerEntry> findTop8ByOwnerIdAndDeletedAtIsNullOrderByEntryDateDescIdDesc(Long ownerId);

    java.util.Optional<LedgerEntry> findTop1ByOwnerIdAndDeletedAtIsNullOrderByEntryDateAscIdAsc(Long ownerId);

    java.util.Optional<LedgerEntry> findTop1ByOwnerIdAndDeletedAtIsNullOrderByEntryDateDescIdDesc(Long ownerId);

    boolean existsByOwnerIdAndDeletedAtIsNullAndEntryDateAndTitleAndAmount(Long ownerId, LocalDate entryDate, String title, java.math.BigDecimal amount);

    java.util.Optional<LedgerEntry> findByIdAndOwnerIdAndDeletedAtIsNull(Long id, Long ownerId);

    java.util.Optional<LedgerEntry> findByIdAndOwnerIdAndDeletedAtIsNotNull(Long id, Long ownerId);

    java.util.Optional<LedgerEntry> findByOwnerIdAndTravelRecordIdAndDeletedAtIsNull(Long ownerId, Long travelRecordId);

    List<LedgerEntry> findAllByOwnerIdAndDeletedAtIsNullAndIdIn(Long ownerId, Collection<Long> ids);

    List<LedgerEntry> findAllByOwnerIdAndIdIn(Long ownerId, Collection<Long> ids);

    Page<LedgerEntry> findAllByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDescEntryDateDescIdDesc(Long ownerId, Pageable pageable);

    @Query(
            value = """
                    select entry
                    from LedgerEntry entry
                    join entry.categoryGroup categoryGroup
                    left join entry.categoryDetail categoryDetail
                    join entry.paymentMethod paymentMethod
                    where entry.owner.id = :userId
                      and entry.deletedAt is null
                      and entry.entryDate between :from and :to
                      and (
                            :keyword is null
                            or entry.title like concat('%', :keyword, '%')
                            or entry.memo like concat('%', :keyword, '%')
                            or categoryGroup.name like concat('%', :keyword, '%')
                            or categoryDetail.name like concat('%', :keyword, '%')
                            or paymentMethod.name like concat('%', :keyword, '%')
                      )
                      and (:entryType is null or entry.entryType = :entryType)
                      and (
                            (:paymentMethodOther = true and paymentMethod.active = false)
                            or (:paymentMethodOther = false and (:paymentMethodId is null or paymentMethod.id = :paymentMethodId))
                      )
                      and (
                            (:categoryGroupOther = true and categoryGroup.active = false)
                            or (:categoryGroupOther = false and (:categoryGroupId is null or categoryGroup.id = :categoryGroupId))
                      )
                      and (
                            (:categoryDetailOther = true and (categoryDetail is null or categoryDetail.active = false))
                            or (:categoryDetailOther = false and (:categoryDetailId is null or categoryDetail.id = :categoryDetailId))
                      )
                      and (:minAmount is null or entry.amount >= :minAmount)
                      and (:maxAmount is null or entry.amount <= :maxAmount)
                    """,
            countQuery = """
                    select count(entry)
                    from LedgerEntry entry
                    join entry.categoryGroup categoryGroup
                    left join entry.categoryDetail categoryDetail
                    join entry.paymentMethod paymentMethod
                    where entry.owner.id = :userId
                      and entry.deletedAt is null
                      and entry.entryDate between :from and :to
                      and (
                            :keyword is null
                            or entry.title like concat('%', :keyword, '%')
                            or entry.memo like concat('%', :keyword, '%')
                            or categoryGroup.name like concat('%', :keyword, '%')
                            or categoryDetail.name like concat('%', :keyword, '%')
                            or paymentMethod.name like concat('%', :keyword, '%')
                      )
                      and (:entryType is null or entry.entryType = :entryType)
                      and (
                            (:paymentMethodOther = true and paymentMethod.active = false)
                            or (:paymentMethodOther = false and (:paymentMethodId is null or paymentMethod.id = :paymentMethodId))
                      )
                      and (
                            (:categoryGroupOther = true and categoryGroup.active = false)
                            or (:categoryGroupOther = false and (:categoryGroupId is null or categoryGroup.id = :categoryGroupId))
                      )
                      and (
                            (:categoryDetailOther = true and (categoryDetail is null or categoryDetail.active = false))
                            or (:categoryDetailOther = false and (:categoryDetailId is null or categoryDetail.id = :categoryDetailId))
                      )
                      and (:minAmount is null or entry.amount >= :minAmount)
                      and (:maxAmount is null or entry.amount <= :maxAmount)
                    """
    )
    Page<LedgerEntry> searchPageByOwnerIdAndFilters(
            @Param("userId") Long userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("keyword") String keyword,
            @Param("entryType") com.playdata.calen.ledger.domain.EntryType entryType,
            @Param("paymentMethodId") Long paymentMethodId,
            @Param("categoryGroupId") Long categoryGroupId,
            @Param("categoryDetailId") Long categoryDetailId,
            @Param("paymentMethodOther") boolean paymentMethodOther,
            @Param("categoryGroupOther") boolean categoryGroupOther,
            @Param("categoryDetailOther") boolean categoryDetailOther,
            @Param("minAmount") java.math.BigDecimal minAmount,
            @Param("maxAmount") java.math.BigDecimal maxAmount,
            Pageable pageable
    );

    @Query("""
            select
                coalesce(sum(case when entry.entryType = :incomeType then entry.amount else 0 end), 0) as income,
                coalesce(sum(case when entry.entryType = :expenseType then entry.amount else 0 end), 0) as expense,
                count(entry) as entryCount
            from LedgerEntry entry
            join entry.categoryGroup categoryGroup
            left join entry.categoryDetail categoryDetail
            join entry.paymentMethod paymentMethod
            where entry.owner.id = :userId
              and entry.deletedAt is null
              and entry.entryDate between :from and :to
              and (
                    :keyword is null
                    or entry.title like concat('%', :keyword, '%')
                    or entry.memo like concat('%', :keyword, '%')
                    or categoryGroup.name like concat('%', :keyword, '%')
                    or categoryDetail.name like concat('%', :keyword, '%')
                    or paymentMethod.name like concat('%', :keyword, '%')
              )
              and (:entryTypeFilter is null or entry.entryType = :entryTypeFilter)
              and (
                    (:paymentMethodOther = true and paymentMethod.active = false)
                    or (:paymentMethodOther = false and (:paymentMethodId is null or paymentMethod.id = :paymentMethodId))
              )
              and (
                    (:categoryGroupOther = true and categoryGroup.active = false)
                    or (:categoryGroupOther = false and (:categoryGroupId is null or categoryGroup.id = :categoryGroupId))
              )
              and (
                    (:categoryDetailOther = true and (categoryDetail is null or categoryDetail.active = false))
                    or (:categoryDetailOther = false and (:categoryDetailId is null or categoryDetail.id = :categoryDetailId))
              )
              and (:minAmount is null or entry.amount >= :minAmount)
              and (:maxAmount is null or entry.amount <= :maxAmount)
            """)
    SearchSummaryAggregate summarizeAmountsByOwnerIdAndFilters(
            @Param("userId") Long userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("keyword") String keyword,
            @Param("entryTypeFilter") com.playdata.calen.ledger.domain.EntryType entryTypeFilter,
            @Param("paymentMethodId") Long paymentMethodId,
            @Param("categoryGroupId") Long categoryGroupId,
            @Param("categoryDetailId") Long categoryDetailId,
            @Param("paymentMethodOther") boolean paymentMethodOther,
            @Param("categoryGroupOther") boolean categoryGroupOther,
            @Param("categoryDetailOther") boolean categoryDetailOther,
            @Param("minAmount") BigDecimal minAmount,
            @Param("maxAmount") BigDecimal maxAmount,
            @Param("incomeType") com.playdata.calen.ledger.domain.EntryType incomeType,
            @Param("expenseType") com.playdata.calen.ledger.domain.EntryType expenseType
    );

    @Query("""
            select
                coalesce(sum(case when entry.entryType = :incomeType then entry.amount else 0 end), 0) as income,
                coalesce(sum(case when entry.entryType = :expenseType then entry.amount else 0 end), 0) as expense,
                count(entry) as entryCount
            from LedgerEntry entry
            where entry.owner.id = :userId
              and entry.deletedAt is null
              and entry.entryDate between :from and :to
            """)
    LedgerAmountAggregate aggregateAmountsByOwnerIdAndDateRange(
            @Param("userId") Long userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("incomeType") com.playdata.calen.ledger.domain.EntryType incomeType,
            @Param("expenseType") com.playdata.calen.ledger.domain.EntryType expenseType
    );

    @Query("""
            select
                entry.entryDate as entryDate,
                coalesce(sum(case when entry.entryType = :incomeType then entry.amount else 0 end), 0) as income,
                coalesce(sum(case when entry.entryType = :expenseType then entry.amount else 0 end), 0) as expense,
                count(entry) as entryCount
            from LedgerEntry entry
            where entry.owner.id = :userId
              and entry.deletedAt is null
              and entry.entryDate between :from and :to
            group by entry.entryDate
            order by entry.entryDate asc
            """)
    List<DailyAmountAggregate> aggregateDailyAmountsByOwnerIdAndDateRange(
            @Param("userId") Long userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("incomeType") com.playdata.calen.ledger.domain.EntryType incomeType,
            @Param("expenseType") com.playdata.calen.ledger.domain.EntryType expenseType
    );

    @Query("""
            select
                categoryGroup.name as groupName,
                coalesce(categoryDetail.name, '미분류') as detailName,
                coalesce(sum(entry.amount), 0) as totalAmount,
                count(entry) as entryCount
            from LedgerEntry entry
            join entry.categoryGroup categoryGroup
            left join entry.categoryDetail categoryDetail
            where entry.owner.id = :userId
              and entry.deletedAt is null
              and entry.entryDate between :from and :to
              and (:entryType is null or entry.entryType = :entryType)
            group by categoryGroup.name, categoryDetail.name
            order by sum(entry.amount) desc
            """)
    List<CategoryBreakdownAggregate> aggregateCategoryBreakdownByOwnerIdAndDateRange(
            @Param("userId") Long userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("entryType") com.playdata.calen.ledger.domain.EntryType entryType
    );

    @Query("""
            select
                paymentMethod.name as paymentMethodName,
                paymentMethod.kind as kind,
                coalesce(sum(entry.amount), 0) as totalAmount,
                count(entry) as entryCount
            from LedgerEntry entry
            join entry.paymentMethod paymentMethod
            where entry.owner.id = :userId
              and entry.deletedAt is null
              and entry.entryDate between :from and :to
            group by paymentMethod.id, paymentMethod.name, paymentMethod.kind
            order by sum(entry.amount) desc
            """)
    List<PaymentBreakdownAggregate> aggregatePaymentBreakdownByOwnerIdAndDateRange(
            @Param("userId") Long userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    @Query("""
            select coalesce(sum(entry.amount), 0)
            from LedgerEntry entry
            where entry.deletedAt is null
              and entry.entryType = :entryType
            """)
    java.math.BigDecimal sumAmountByEntryTypeAndDeletedAtIsNull(
            @Param("entryType") com.playdata.calen.ledger.domain.EntryType entryType
    );

    @Modifying
    @Query("""
            delete from LedgerEntry entry
            where entry.owner.id = :userId
              and entry.deletedAt is not null
            """)
    int deleteAllDeletedByOwnerId(@Param("userId") Long userId);

    interface SearchSummaryAggregate {
        BigDecimal getIncome();

        BigDecimal getExpense();

        long getEntryCount();
    }

    interface LedgerAmountAggregate {
        BigDecimal getIncome();

        BigDecimal getExpense();

        long getEntryCount();
    }

    interface DailyAmountAggregate {
        LocalDate getEntryDate();

        BigDecimal getIncome();

        BigDecimal getExpense();

        long getEntryCount();
    }

    interface CategoryBreakdownAggregate {
        String getGroupName();

        String getDetailName();

        BigDecimal getTotalAmount();

        long getEntryCount();
    }

    interface PaymentBreakdownAggregate {
        String getPaymentMethodName();

        PaymentMethodKind getKind();

        BigDecimal getTotalAmount();

        long getEntryCount();
    }
}
