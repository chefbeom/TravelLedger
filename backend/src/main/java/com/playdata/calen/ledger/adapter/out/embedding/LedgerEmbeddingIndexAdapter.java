package com.playdata.calen.ledger.adapter.out.embedding;

import com.playdata.calen.common.embedding.*;
import com.playdata.calen.ledger.application.port.out.LedgerEmbeddingIndexPort;
import com.playdata.calen.ledger.domain.model.LedgerEmbeddingSyncData.*;
import com.playdata.calen.ledger.embedding.LedgerEmbeddingPointIds;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class LedgerEmbeddingIndexAdapter implements LedgerEmbeddingIndexPort {
    private final EmbeddingApiClient client;
    @Override public void upsert(List<Document> documents) {
        try {
            client.upsertDocuments(documents.stream().map(d -> new EmbeddingDocument(d.id(),d.text(),d.metadata())).toList());
        } catch (EmbeddingApiException failure) { throw new Failure(failure.getHttpStatus(),failure.isRetryable()); }
    }
    @Override public void delete(List<Key> sources) {
        try {
            client.deleteDocuments(sources.stream().map(key -> LedgerEmbeddingPointIds.forRecord(
                    key.ownerId(),LedgerEmbeddingJobsAdapter.type(key.recordType()),key.sourceId())).toList());
        } catch (EmbeddingApiException failure) { throw new Failure(failure.getHttpStatus(),failure.isRetryable()); }
    }
}
