package com.playdata.calen.recordmap.domain.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RecordMapWriteData {
    private RecordMapWriteData() {}

    public record PlaceDraft(String name, String memo, String country, String region, Double latitude,
            Double longitude, boolean visited, List<Long> collectionIds) {
        public PlaceDraft { collectionIds = copyIds(collectionIds); }
    }

    public record CollectionDraft(String name, String memo, String colorHex) {}

    public record CollectionResult(Long id, String name, String memo, String colorHex) {}

    public record VisitDraft(String title, String memo, LocalDate date, LocalTime time) {}

    public record TravelImport(Long recordId, List<Long> collectionIds) {
        public TravelImport { collectionIds = copyIds(collectionIds); }
    }

    public record TravelSource(Long recordId, String title, String placeName, String country, String region,
            Double latitude, Double longitude) {}

    private static List<Long> copyIds(List<Long> ids) {
        return ids == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(ids));
    }
}
