package com.playdata.calen.common.embedding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** An immutable transfer document; metadata must come from trusted backend sources. */
public record EmbeddingDocument(UUID id, String text, Map<String, Object> metadata) {

    public EmbeddingDocument {
        Objects.requireNonNull(id, "document id is required");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("document text is required");
        Objects.requireNonNull(metadata, "document metadata is required");
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    @Override
    public String toString() {
        return "EmbeddingDocument[id=" + id + ", text=<redacted>, metadata=<redacted>]";
    }
}
