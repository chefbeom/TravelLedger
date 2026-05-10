package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.LedgerEntryChangeHistory;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryChangeHistoryRepository extends JpaRepository<LedgerEntryChangeHistory, Long> {

    Page<LedgerEntryChangeHistory> findAllByOwnerIdOrderByCreatedAtDescIdDesc(Long ownerId, Pageable pageable);

    Optional<LedgerEntryChangeHistory> findByIdAndOwnerId(Long id, Long ownerId);
}
