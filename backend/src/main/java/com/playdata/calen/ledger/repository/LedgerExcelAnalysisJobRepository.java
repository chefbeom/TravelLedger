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
    @Query("""
            select j from LedgerExcelAnalysisJob j
            where j.status = :status and j.createdAt < :before
              and (:afterTime is null or j.createdAt > :afterTime or (j.createdAt = :afterTime and j.id > :afterId))
            order by j.createdAt asc, j.id asc
            """)
    List<LedgerExcelAnalysisJob> findRecoveryPage(@Param("status") String status, @Param("before") LocalDateTime before,
            @Param("afterTime") LocalDateTime afterTime, @Param("afterId") String afterId, Pageable pageable);
}
