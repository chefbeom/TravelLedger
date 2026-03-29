package com.playdata.calen.account.repository;

import com.playdata.calen.account.domain.LoginAuditLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginAuditLogRepository extends JpaRepository<LoginAuditLog, Long> {

    List<LoginAuditLog> findTop100ByOrderByAttemptedAtDescIdDesc();

    long countBySuccessFalseAndAttemptedAtAfter(LocalDateTime attemptedAt);
}
