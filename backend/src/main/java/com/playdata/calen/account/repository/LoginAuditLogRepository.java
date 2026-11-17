package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.LoginAuditLog;
import java.time.LocalDateTime;
import java.util.List;
import jakarta.persistence.LockModeType;
import com.playdata.calen.account.domain.LoginAuditStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginAuditLogRepository extends JpaRepository<LoginAuditLog, Long> {

    Page<LoginAuditLog> findAllByOrderByAttemptedAtDescIdDesc(Pageable pageable);

    long countBySuccessFalseAndAttemptedAtAfter(LocalDateTime attemptedAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select log from LoginAuditLog log
            where log.unknownFailurePrivacyProcessed = false and log.appUser is null
              and log.status in :statuses order by log.id
            """)
    List<LoginAuditLog> findUnclassifiedUnknownFailures(@Param("statuses") List<LoginAuditStatus> statuses, Pageable page);
}
