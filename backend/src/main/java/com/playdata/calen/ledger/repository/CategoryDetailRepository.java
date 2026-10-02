package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.CategoryDetail;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryDetailRepository extends JpaRepository<CategoryDetail, Long> {

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "group")
    List<CategoryDetail> findAllByGroupIdInOrderByDisplayOrderAscIdAsc(java.util.Collection<Long> groupIds);

    List<CategoryDetail> findAllByGroupIdOrderByDisplayOrderAscIdAsc(Long groupId);

    List<CategoryDetail> findAllByGroupIdAndActiveTrueOrderByDisplayOrderAscIdAsc(Long groupId);

    Optional<CategoryDetail> findByGroupIdAndNameIgnoreCase(Long groupId, String name);

    Optional<CategoryDetail> findFirstByGroupIdAndNameIgnoreCaseOrderByIdAsc(Long groupId, String name);

    Optional<CategoryDetail> findByIdAndGroupOwnerId(Long id, Long ownerId);
}
