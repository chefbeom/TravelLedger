package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.LedgerAiAnalysisHistory;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisMode;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisPeriod;
import com.playdata.calen.ledger.domain.LedgerAiAnalysisStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerAiAnalysisHistoryRepository extends JpaRepository<LedgerAiAnalysisHistory, Long> {

    @Query("""
            select history
            from LedgerAiAnalysisHistory history
            where history.owner.id = :ownerId
              and (:mode is null or history.mode = :mode)
              and (:periodType is null or history.periodType = :periodType)
              and (:createdFrom is null or history.createdAt >= :createdFrom)
              and (:createdToExclusive is null or history.createdAt < :createdToExclusive)
              and (
                    :comparisonOnly is null
                    or (:comparisonOnly = true and history.compareFromDate is not null)
                    or (:comparisonOnly = false and history.compareFromDate is null)
              )
            order by history.createdAt desc, history.id desc
            """)
    Page<LedgerAiAnalysisHistory> searchHistories(
            @Param("ownerId") Long ownerId,
            @Param("mode") LedgerAiAnalysisMode mode,
            @Param("periodType") LedgerAiAnalysisPeriod periodType,
            @Param("createdFrom") LocalDateTime createdFrom,
            @Param("createdToExclusive") LocalDateTime createdToExclusive,
            @Param("comparisonOnly") Boolean comparisonOnly,
            Pageable pageable
    );

    Page<LedgerAiAnalysisHistory> findAllByOwnerIdOrderByCreatedAtDescIdDesc(Long ownerId, Pageable pageable);

    Page<LedgerAiAnalysisHistory> findAllByOwnerIdAndModeOrderByCreatedAtDescIdDesc(
            Long ownerId,
            LedgerAiAnalysisMode mode,
            Pageable pageable
    );

    Page<LedgerAiAnalysisHistory> findAllByOwnerIdAndPeriodTypeOrderByCreatedAtDescIdDesc(
            Long ownerId,
            LedgerAiAnalysisPeriod periodType,
            Pageable pageable
    );

    Page<LedgerAiAnalysisHistory> findAllByOwnerIdAndModeAndPeriodTypeOrderByCreatedAtDescIdDesc(
            Long ownerId,
            LedgerAiAnalysisMode mode,
            LedgerAiAnalysisPeriod periodType,
            Pageable pageable
    );

    Optional<LedgerAiAnalysisHistory> findByIdAndOwnerId(Long id, Long ownerId);

    long countByOwnerId(Long ownerId);

    Optional<LedgerAiAnalysisHistory> findTop1ByOwnerIdAndStatusAndModeAndPeriodTypeAndFromDateAndToDateAndCompareFromDateAndCompareToDateOrderByCreatedAtDescIdDesc(
            Long ownerId,
            LedgerAiAnalysisStatus status,
            LedgerAiAnalysisMode mode,
            LedgerAiAnalysisPeriod periodType,
            LocalDate fromDate,
            LocalDate toDate,
            LocalDate compareFromDate,
            LocalDate compareToDate
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from LedgerAiAnalysisHistory history where history.owner.id = :ownerId")
    int deleteAllByOwnerId(@Param("ownerId") Long ownerId);
}