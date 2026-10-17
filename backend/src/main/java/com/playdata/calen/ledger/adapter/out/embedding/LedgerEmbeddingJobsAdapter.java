package com.playdata.calen.ledger.adapter.out.embedding;

import com.playdata.calen.ledger.application.port.out.LedgerEmbeddingJobsPort;
import com.playdata.calen.ledger.domain.model.LedgerEmbeddingSyncData.*;
import com.playdata.calen.ledger.embedding.LedgerEmbeddingRecordType;
import com.playdata.calen.ledger.embedding.sync.LedgerEmbeddingSyncStore;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Compatibility bridge: SQL, claim tokens, isolation, timeout and retry clocks stay in the existing store. */
@Component
@RequiredArgsConstructor
public class LedgerEmbeddingJobsAdapter implements LedgerEmbeddingJobsPort {
    private final LedgerEmbeddingSyncStore store;
    @Override public void recoverExpired() { store.recoverExpired(); }
    @Override public List<Claim> claimBatch(int limit) { return store.claimBatch(limit).stream().map(this::value).toList(); }
    @Override public Prepared prepareLatest(Claim claim) {
        var prepared = store.prepareLatest(legacy(claim));
        if (prepared == null) return null;
        var doc = prepared.document();
        return new Prepared(value(prepared.claim()), doc == null ? null : new Document(doc.id(),doc.text(),doc.metadata()));
    }
    @Override public boolean isCurrent(Claim claim) { return store.isCurrent(legacy(claim)); }
    @Override public void releaseSuperseded(Claim claim) { store.releaseSuperseded(legacy(claim)); }
    @Override public void succeeded(Claim claim) { store.succeeded(legacy(claim)); }
    @Override public void failed(Claim claim, boolean retryable, String code) { store.failed(legacy(claim),retryable,code); }

    private Claim value(LedgerEmbeddingSyncStore.Claim claim) {
        return new Claim(new Key(claim.ownerId(),claim.type().metadataValue(),claim.sourceId()),claim.revision(),claim.token(),claim.attempts());
    }
    private LedgerEmbeddingSyncStore.Claim legacy(Claim claim) {
        return new LedgerEmbeddingSyncStore.Claim(claim.key().ownerId(),type(claim.key().recordType()),claim.key().sourceId(),
                claim.revision(),claim.token(),claim.attempts());
    }
    static LedgerEmbeddingRecordType type(String value) {
        for (var type : LedgerEmbeddingRecordType.values()) if (type.metadataValue().equals(value)) return type;
        throw new IllegalArgumentException("unsupported embedding record type");
    }
}
