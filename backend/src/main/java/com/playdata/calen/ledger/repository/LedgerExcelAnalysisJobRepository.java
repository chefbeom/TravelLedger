package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.LedgerExcelAnalysisJob;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerExcelAnalysisJobRepository extends JpaRepository<LedgerExcelAnalysisJob, String> {
    Optional<LedgerExcelAnalysisJob> findByIdAndOwnerId(String id, Long ownerId);
    long countByOwnerIdAndStatus(Long ownerId, String status);
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LedgerExcelAnalysisJob j
            set j.status = 'COMPLETED', j.version = j.version + 1, j.resultJson = :result,
                j.inputJson = null, j.errorMessage = null
            where j.id = :id and j.ownerId = :ownerId and j.status = 'PROCESSING' and j.version = :version
            """)
    int completeProcessing(@Param("id") String id, @Param("ownerId") Long ownerId, @Param("version") Long version, @Param("result") String result);

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LedgerExcelAnalysisJob j
            set j.status = 'FAILED', j.version = j.version + 1, j.inputJson = null, j.errorMessage = :error
            where j.id = :id and j.ownerId = :ownerId and j.status = 'PROCESSING' and j.version = :version
            """)
    int failProcessing(@Param("id") String id, @Param("ownerId") Long ownerId, @Param("version") Long version, @Param("error") String error);
    @Query("""
            select j from LedgerExcelAnalysisJob j
            where j.status = :status and j.createdAt < :before
              and (:afterTime is null or j.createdAt > :afterTime or (j.createdAt = :afterTime and j.id > :afterId))
            order by j.createdAt asc, j.id asc
            """)
    List<LedgerExcelAnalysisJob> findRecoveryPage(@Param("status") String status, @Param("before") LocalDateTime before,
            @Param("afterTime") LocalDateTime afterTime, @Param("afterId") String afterId, Pageable pageable);
}
