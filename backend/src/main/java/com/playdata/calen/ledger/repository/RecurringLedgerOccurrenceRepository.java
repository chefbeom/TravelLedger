package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.RecurringLedgerOccurrence;
import com.playdata.calen.ledger.domain.RecurringLedgerOccurrenceStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringLedgerOccurrenceRepository extends JpaRepository<RecurringLedgerOccurrence, Long> {

    Optional<RecurringLedgerOccurrence> findByRuleIdAndScheduledDate(Long ruleId, LocalDate scheduledDate);

    List<RecurringLedgerOccurrence> findAllByRuleOwnerIdAndStatusOrderByScheduledDateAscIdAsc(
            Long ownerId,
            RecurringLedgerOccurrenceStatus status
    );

    Optional<RecurringLedgerOccurrence> findByIdAndRuleOwnerId(Long id, Long ownerId);

    void deleteAllByRuleId(Long ruleId);
}
