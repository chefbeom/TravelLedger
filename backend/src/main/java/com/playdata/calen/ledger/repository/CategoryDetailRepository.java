package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.CategoryDetail;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryDetailRepository extends JpaRepository<CategoryDetail, Long> {

    List<CategoryDetail> findAllByGroupIdOrderByDisplayOrderAscIdAsc(Long groupId);

    Optional<CategoryDetail> findByIdAndGroupOwnerId(Long id, Long ownerId);
}
