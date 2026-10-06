package com.playdata.calen.common.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

public final class EmbeddingApiModels {

    private EmbeddingApiModels() { }

    public record EmbedRequest(List<String> texts) {
        public EmbedRequest { texts = List.copyOf(texts); }

        @Override
        public String toString() { return "EmbedRequest[textCount=" + texts.size() + "]"; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbedResponse(List<List<Double>> vectors, Integer dimensions) { }

    public record UpsertRequest(List<EmbeddingDocument> documents) {
        public UpsertRequest { documents = List.copyOf(documents); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpsertResponse(String status, Integer upserted, List<UUID> ids) { }

    public record DeleteRequest(List<UUID> ids) {
        public DeleteRequest { ids = List.copyOf(ids); }
    }

    /** requested counts distinct requested IDs, not the number of existing points deleted. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeleteResponse(String status, Integer requested, List<UUID> ids) { }
}
