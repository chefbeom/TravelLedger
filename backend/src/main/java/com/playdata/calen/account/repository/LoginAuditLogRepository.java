package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.LoginAuditLog;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginAuditLogRepository extends JpaRepository<LoginAuditLog, Long> {

    Page<LoginAuditLog> findAllByOrderByAttemptedAtDescIdDesc(Pageable pageable);

    long countBySuccessFalseAndAttemptedAtAfter(LocalDateTime attemptedAt);
}
