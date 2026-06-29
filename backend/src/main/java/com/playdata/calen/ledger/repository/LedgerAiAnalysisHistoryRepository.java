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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from LedgerAiAnalysisHistory history where history.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from LedgerAiAnalysisHistory history where history.id = :id and history.owner.id = :ownerId")
    int deleteByIdAndOwnerId(@Param("id") Long id, @Param("ownerId") Long ownerId);

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


    @Query("""
            select history
            from LedgerAiAnalysisHistory history
            where history.owner.id = :ownerId
              and history.status = :status
              and history.provider = :provider
              and history.model = :model
              and history.mode = :mode
              and history.periodType = :periodType
              and history.fromDate = :fromDate
              and history.toDate = :toDate
              and ((:compareFromDate is null and history.compareFromDate is null) or history.compareFromDate = :compareFromDate)
              and ((:compareToDate is null and history.compareToDate is null) or history.compareToDate = :compareToDate)
              and (:createdAfter is null or history.createdAt >= :createdAfter)
            order by history.createdAt desc, history.id desc
            """)
    Optional<LedgerAiAnalysisHistory> findLatestMatchingCompletedAnalysis(
            @Param("ownerId") Long ownerId,
            @Param("status") LedgerAiAnalysisStatus status,
            @Param("provider") String provider,
            @Param("model") String model,
            @Param("mode") LedgerAiAnalysisMode mode,
            @Param("periodType") LedgerAiAnalysisPeriod periodType,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("compareFromDate") LocalDate compareFromDate,
            @Param("compareToDate") LocalDate compareToDate,
            @Param("createdAfter") LocalDateTime createdAfter
    );
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from LedgerAiAnalysisHistory history where history.owner.id = :ownerId")
    int deleteAllByOwnerId(@Param("ownerId") Long ownerId);
}