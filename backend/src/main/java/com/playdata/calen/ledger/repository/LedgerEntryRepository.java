package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.LedgerEntry;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findAllByOwnerIdOrderByEntryDateAscIdAsc(Long ownerId);

    List<LedgerEntry> findAllByOwnerIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(Long ownerId, LocalDate from, LocalDate to);

    List<LedgerEntry> findTop8ByOwnerIdOrderByEntryDateDescIdDesc(Long ownerId);

    java.util.Optional<LedgerEntry> findTop1ByOwnerIdOrderByEntryDateAscIdAsc(Long ownerId);

    java.util.Optional<LedgerEntry> findTop1ByOwnerIdOrderByEntryDateDescIdDesc(Long ownerId);

    boolean existsByOwnerIdAndEntryDateAndTitleAndAmount(Long ownerId, LocalDate entryDate, String title, java.math.BigDecimal amount);

    java.util.Optional<LedgerEntry> findByIdAndOwnerId(Long id, Long ownerId);

    @Query(
            value = """
                    select entry
                    from LedgerEntry entry
                    join entry.categoryGroup categoryGroup
                    left join entry.categoryDetail categoryDetail
                    join entry.paymentMethod paymentMethod
                    where entry.owner.id = :userId
                      and entry.entryDate between :from and :to
                      and (
                            :keyword is null
                            or lower(entry.title) like concat('%', :keyword, '%')
                            or lower(coalesce(entry.memo, '')) like concat('%', :keyword, '%')
                            or lower(categoryGroup.name) like concat('%', :keyword, '%')
                            or lower(coalesce(categoryDetail.name, '')) like concat('%', :keyword, '%')
                            or lower(paymentMethod.name) like concat('%', :keyword, '%')
                      )
                      and (:entryType is null or entry.entryType = :entryType)
                      and (:paymentMethodId is null or paymentMethod.id = :paymentMethodId)
                      and (:categoryGroupId is null or categoryGroup.id = :categoryGroupId)
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
                      and entry.entryDate between :from and :to
                      and (
                            :keyword is null
                            or lower(entry.title) like concat('%', :keyword, '%')
                            or lower(coalesce(entry.memo, '')) like concat('%', :keyword, '%')
                            or lower(categoryGroup.name) like concat('%', :keyword, '%')
                            or lower(coalesce(categoryDetail.name, '')) like concat('%', :keyword, '%')
                            or lower(paymentMethod.name) like concat('%', :keyword, '%')
                      )
                      and (:entryType is null or entry.entryType = :entryType)
                      and (:paymentMethodId is null or paymentMethod.id = :paymentMethodId)
                      and (:categoryGroupId is null or categoryGroup.id = :categoryGroupId)
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
            @Param("minAmount") java.math.BigDecimal minAmount,
            @Param("maxAmount") java.math.BigDecimal maxAmount,
            Pageable pageable
    );

    @Query("""
            select coalesce(sum(entry.amount), 0)
            from LedgerEntry entry
            join entry.categoryGroup categoryGroup
            left join entry.categoryDetail categoryDetail
            join entry.paymentMethod paymentMethod
            where entry.owner.id = :userId
              and entry.entryDate between :from and :to
              and (
                    :keyword is null
                    or lower(entry.title) like concat('%', :keyword, '%')
                    or lower(coalesce(entry.memo, '')) like concat('%', :keyword, '%')
                    or lower(categoryGroup.name) like concat('%', :keyword, '%')
                    or lower(coalesce(categoryDetail.name, '')) like concat('%', :keyword, '%')
                    or lower(paymentMethod.name) like concat('%', :keyword, '%')
              )
              and (:entryTypeFilter is null or entry.entryType = :entryTypeFilter)
              and (:paymentMethodId is null or paymentMethod.id = :paymentMethodId)
              and (:categoryGroupId is null or categoryGroup.id = :categoryGroupId)
              and (:minAmount is null or entry.amount >= :minAmount)
              and (:maxAmount is null or entry.amount <= :maxAmount)
              and entry.entryType = :entryTypeToSum
            """)
    java.math.BigDecimal sumAmountByOwnerIdAndFilters(
            @Param("userId") Long userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("keyword") String keyword,
            @Param("entryTypeFilter") com.playdata.calen.ledger.domain.EntryType entryTypeFilter,
            @Param("paymentMethodId") Long paymentMethodId,
            @Param("categoryGroupId") Long categoryGroupId,
            @Param("minAmount") java.math.BigDecimal minAmount,
            @Param("maxAmount") java.math.BigDecimal maxAmount,
            @Param("entryTypeToSum") com.playdata.calen.ledger.domain.EntryType entryTypeToSum
    );
}
