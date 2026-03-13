package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.LedgerEntry;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findAllByOwnerIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(Long ownerId, LocalDate from, LocalDate to);

    List<LedgerEntry> findTop8ByOwnerIdOrderByEntryDateDescIdDesc(Long ownerId);

    java.util.Optional<LedgerEntry> findByIdAndOwnerId(Long id, Long ownerId);
}
