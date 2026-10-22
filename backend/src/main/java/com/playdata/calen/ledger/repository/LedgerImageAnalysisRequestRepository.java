package com.playdata.calen.ledger.repository;

import com.playdata.calen.ledger.domain.LedgerImageAnalysisRequest;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface LedgerImageAnalysisRequestRepository extends JpaRepository<LedgerImageAnalysisRequest, Long> {

    Page<LedgerImageAnalysisRequest> findAllByOwnerIdOrderByCreatedAtDescIdDesc(Long ownerId, Pageable pageable);

    Optional<LedgerImageAnalysisRequest> findByIdAndOwnerId(Long id, Long ownerId);

    Optional<LedgerImageAnalysisRequest> findByClientRequestIdAndOwnerId(String clientRequestId, Long ownerId);

    /** Restart the same history without changing its original identity, image or batch metadata. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LedgerImageAnalysisRequest request
            set request.status = com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus.PROCESSING,
                request.version = request.version + 1, request.updatedAt = :now,
                request.documentType = :documentType, request.effectivePrompt = :prompt,
                request.summary = 'AI 이미지 분석을 다시 요청했습니다.', request.errorMessage = null,
                request.resultJson = null, request.rawText = null, request.completedAt = null, request.cancelledAt = null
            where request.id = :id and request.owner.id = :ownerId and request.version = :version
              and request.status <> com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus.PROCESSING
            """)
    int restart(@Param("id") Long id, @Param("ownerId") Long ownerId, @Param("version") Long version,
            @Param("documentType") String documentType, @Param("prompt") String prompt, @Param("now") LocalDateTime now);

    interface CompletedReviewRow {
        Long getId();
        String getResultJson();
    }

    @Query("""
            select request.id as id, request.resultJson as resultJson
            from LedgerImageAnalysisRequest request
            where request.owner.id = :ownerId
              and request.status = com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus.COMPLETED
              and request.id > :afterId
            order by request.id asc
            """)
    java.util.List<CompletedReviewRow> findCompletedReviewRows(@Param("ownerId") Long ownerId,
            @Param("afterId") Long afterId, Pageable pageable);

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LedgerImageAnalysisRequest request
            set request.imageObjectKey = :objectKey, request.imageStoredAt = :now, request.updatedAt = :now,
                request.version = request.version + 1
            where request.id = :id and request.owner.id = :ownerId
            """)
    int attachStoredImage(@Param("id") Long id, @Param("ownerId") Long ownerId,
            @Param("objectKey") String objectKey, @Param("now") LocalDateTime now);

    // Short, conditional writes must not merge the detached object held during remote inference.
    // Increment the version so ordinary JPA edits cannot overwrite a terminal transition either.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LedgerImageAnalysisRequest request
            set request.status = com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus.COMPLETED,
                request.version = request.version + 1, request.completedAt = :now, request.updatedAt = :now,
                request.documentType = :documentType, request.rawText = :rawText,
                request.summary = :summary, request.resultJson = :resultJson, request.errorMessage = null
            where request.id = :id and request.owner.id = :ownerId
              and request.status = com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus.PROCESSING
            """)
    int completeProcessing(@Param("id") Long id, @Param("ownerId") Long ownerId,
            @Param("documentType") String documentType, @Param("rawText") String rawText,
            @Param("summary") String summary, @Param("resultJson") String resultJson, @Param("now") LocalDateTime now);

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LedgerImageAnalysisRequest request
            set request.status = com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus.COMPLETED,
                request.version = request.version + 1, request.completedAt = :now, request.updatedAt = :now,
                request.documentType = :documentType, request.rawText = :rawText,
                request.summary = :summary, request.resultJson = :resultJson, request.errorMessage = null
            where request.id = :id and request.owner.id = :ownerId and request.version = :version
              and request.status = com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus.PROCESSING
            """)
    int completeAttempt(@Param("id") Long id, @Param("ownerId") Long ownerId, @Param("version") Long version,
            @Param("documentType") String documentType, @Param("rawText") String rawText,
            @Param("summary") String summary, @Param("resultJson") String resultJson, @Param("now") LocalDateTime now);

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LedgerImageAnalysisRequest request
            set request.status = com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus.FAILED,
                request.version = request.version + 1, request.completedAt = :now, request.updatedAt = :now,
                request.errorMessage = :errorMessage, request.summary = :summary
            where request.id = :id and request.owner.id = :ownerId
              and request.status = com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus.PROCESSING
            """)
    int failProcessing(@Param("id") Long id, @Param("ownerId") Long ownerId,
            @Param("errorMessage") String errorMessage, @Param("summary") String summary, @Param("now") LocalDateTime now);

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LedgerImageAnalysisRequest request
            set request.status = com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus.FAILED,
                request.version = request.version + 1, request.completedAt = :now, request.updatedAt = :now,
                request.errorMessage = :errorMessage, request.summary = :summary
            where request.id = :id and request.owner.id = :ownerId and request.version = :version
              and request.status = com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus.PROCESSING
            """)
    int failAttempt(@Param("id") Long id, @Param("ownerId") Long ownerId, @Param("version") Long version,
            @Param("errorMessage") String errorMessage, @Param("summary") String summary, @Param("now") LocalDateTime now);

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LedgerImageAnalysisRequest request
            set request.status = com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus.CANCELLED,
                request.version = request.version + 1, request.cancelledAt = :now, request.updatedAt = :now,
                request.summary = :summary
            where request.id = :id and request.owner.id = :ownerId
              and request.status = com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus.PROCESSING
            """)
    int cancelProcessing(@Param("id") Long id, @Param("ownerId") Long ownerId,
            @Param("summary") String summary, @Param("now") LocalDateTime now);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "owner")
    java.util.List<LedgerImageAnalysisRequest> findAllByStatusAndCreatedAtBeforeAndIdGreaterThanOrderByIdAsc(
            com.playdata.calen.ledger.domain.LedgerImageAnalysisStatus status, java.time.LocalDateTime before, Long afterId, Pageable pageable);
}
