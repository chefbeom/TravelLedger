package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.RecurringLedgerRule;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringLedgerRuleRepository extends JpaRepository<RecurringLedgerRule, Long> {

    List<RecurringLedgerRule> findAllByOwnerIdOrderByActiveDescDayOfMonthAscIdAsc(Long ownerId);

    List<RecurringLedgerRule> findAllByActiveTrueAndStartDateLessThanEqual(LocalDate date);

    Optional<RecurringLedgerRule> findByIdAndOwnerId(Long id, Long ownerId);
}
