package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.LedgerClassificationRule;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerClassificationRuleRepository extends JpaRepository<LedgerClassificationRule, Long> {

    List<LedgerClassificationRule> findAllByOwnerIdOrderByPriorityAscIdAsc(Long ownerId);

    List<LedgerClassificationRule> findAllByOwnerIdAndActiveTrueOrderByPriorityAscIdAsc(Long ownerId);

    Optional<LedgerClassificationRule> findByIdAndOwnerId(Long id, Long ownerId);
}