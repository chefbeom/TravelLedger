package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.CategoryGroup;
import com.playdata.calen.ledger.domain.EntryType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryGroupRepository extends JpaRepository<CategoryGroup, Long> {

    boolean existsByOwnerId(Long ownerId);

    List<CategoryGroup> findAllByOwnerIdAndActiveTrueOrderByDisplayOrderAscIdAsc(Long ownerId);

    List<CategoryGroup> findAllByOwnerIdAndEntryTypeAndActiveTrueOrderByDisplayOrderAscIdAsc(Long ownerId, EntryType entryType);

    java.util.Optional<CategoryGroup> findByIdAndOwnerId(Long id, Long ownerId);
}
