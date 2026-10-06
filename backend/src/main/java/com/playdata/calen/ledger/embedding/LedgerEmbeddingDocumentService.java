package com.playdata.calen.ledger.embedding;

import com.playdata.calen.common.embedding.EmbeddingDocument;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.ledger.repository.LedgerEntryRepository;
import com.playdata.calen.ledger.repository.RecurringLedgerRuleRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Prepares detached transfer documents only; never performs network IO or schedules work. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LedgerEmbeddingDocumentService {

    private final LedgerEntryRepository entryRepository;
    private final RecurringLedgerRuleRepository ruleRepository;
    private final LedgerEmbeddingDocumentFactory documentFactory;

    /** authenticatedOwnerId must come from the principal or a trusted backend job, not a request body. */
    public EmbeddingDocument prepareEntry(Long authenticatedOwnerId, Long sourceId) {
        requireIds(authenticatedOwnerId, sourceId);
        return documentFactory.fromEntry(entryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(sourceId, authenticatedOwnerId)
                .orElseThrow(() -> new NotFoundException("임베딩할 가계부 거래를 찾을 수 없습니다.")));
    }

    /** Includes inactive rules; their active metadata supports an explicit retrieval filter later. */
    public EmbeddingDocument prepareRule(Long authenticatedOwnerId, Long sourceId) {
        requireIds(authenticatedOwnerId, sourceId);
        return documentFactory.fromRule(ruleRepository.findByIdAndOwnerId(sourceId, authenticatedOwnerId)
                .orElseThrow(() -> new NotFoundException("임베딩할 정기 입출금 규칙을 찾을 수 없습니다.")));
    }

    /** Absence is a normal delete decision, not an exception that marks a joined transaction rollback-only. */
    public Optional<EmbeddingDocument> findCurrentForSync(Long ownerId, LedgerEmbeddingRecordType type, Long sourceId) {
        requireIds(ownerId, sourceId);
        return switch (type) {
            case LEDGER_ENTRY -> entryRepository.findByIdAndOwnerIdAndDeletedAtIsNull(sourceId, ownerId)
                    .map(documentFactory::fromEntry);
            case RECURRING_RULE -> ruleRepository.findByIdAndOwnerId(sourceId, ownerId)
                    .map(documentFactory::fromRule);
        };
    }

    private void requireIds(Long ownerId, Long sourceId) {
        if (ownerId == null || ownerId <= 0 || sourceId == null || sourceId <= 0) {
            throw new IllegalArgumentException("owner and source IDs must be persisted positive IDs");
        }
    }
}
