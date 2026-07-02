package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.LedgerImageAnalysisRequest;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerImageAnalysisRequestRepository extends JpaRepository<LedgerImageAnalysisRequest, Long> {

    Page<LedgerImageAnalysisRequest> findAllByOwnerIdOrderByCreatedAtDescIdDesc(Long ownerId, Pageable pageable);

    Optional<LedgerImageAnalysisRequest> findByIdAndOwnerId(Long id, Long ownerId);

    Optional<LedgerImageAnalysisRequest> findByClientRequestIdAndOwnerId(String clientRequestId, Long ownerId);
}
