package com.playdata.calen.ledger.domain.model;

import java.util.*;

/** Source identity and detached documents only; never carry provider response bodies or credentials. */
public final class LedgerEmbeddingSyncData {
    private LedgerEmbeddingSyncData() {}
    public record Key(long ownerId, String recordType, long sourceId) {}
    public record Claim(Key key, long revision, String token, int attempts) {}
    public record Prepared(Claim claim, Document document) {}
    public record Document(UUID id, String text, Map<String,Object> metadata) {
        public Document {
            Objects.requireNonNull(id, "document id is required");
            if (text == null || text.isBlank()) throw new IllegalArgumentException("document text is required");
            Objects.requireNonNull(metadata, "document metadata is required");
            metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
        @Override public String toString() { return "EmbeddingDocument[id=" + id + ", text=<redacted>, metadata=<redacted>]"; }
    }
}
