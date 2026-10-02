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

    @org.springframework.data.jpa.repository.Query("select rule.id from RecurringLedgerRule rule where rule.active = true and rule.startDate <= :date and rule.id > :afterId order by rule.id")
    List<Long> findDueCandidateIds(@org.springframework.data.repository.query.Param("date") LocalDate date,
            @org.springframework.data.repository.query.Param("afterId") Long afterId, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select rule from RecurringLedgerRule rule where rule.id = :id")
    Optional<RecurringLedgerRule> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}
