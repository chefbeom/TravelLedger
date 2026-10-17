package com.playdata.calen.recordmap.domain.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class RecordMapReadData {
    private RecordMapReadData() {}

    public record Filter(String state, LocalDate from, LocalDate to, int page, String keyword, int size, boolean map) {}
    public record Window(int page, int size) {}
    public record Selection(String state, LocalDate from, LocalDate to, String keyword, Window window) {}
    public record Scope(Long ownerId, Long collectionId, boolean readOnly, boolean includeVisits) {}
    public record CollectionData(Long id, String name, String memo, String colorHex) {}
    public record CollectionResult(Long id, String name, String memo, String colorHex, long placeCount, boolean shared) {}

    public record PlaceData(Long id, String name, String memo, String country, String region, Double latitude,
            Double longitude, boolean manuallyVisited, Long sourceTravelRecordId, List<Long> collectionIds) {
        public PlaceData { collectionIds = List.copyOf(collectionIds); }
    }
    public record PlacePage(List<PlaceData> content, long total, int totalPages) {
        public PlacePage { content = List.copyOf(content); }
    }
    public record PlaceResult(Long id, String name, String memo, String country, String region, Double latitude,
            Double longitude, boolean visited, long visitCount, Long sourceTravelRecordId, List<Long> collectionIds) {
        public PlaceResult { collectionIds = List.copyOf(collectionIds); }
    }

    public enum Origin { RECORD_MAP, TRAVEL }
    public record PhotoData(Long id, String name, Origin origin) {}
    public record TravelPhoto(Long recordId, PhotoData photo) {}
    public record VisitData(Long id, Long placeId, String title, String memo, LocalDate date, LocalTime time) {}
    public record TravelRecordData(Long id, String title, String memo, LocalDate date, LocalTime time, Long planId, String planName) {}
    public record VisitResult(Long id, Origin origin, Long placeId, String title, String memo, LocalDate date,
            LocalTime time, Long travelPlanId, String travelPlanName, List<PhotoData> photos) {
        public VisitResult { photos = List.copyOf(photos); }
    }
    public record OverviewResult(List<CollectionResult> collections, List<PlaceResult> places, List<VisitResult> visits,
            long totalPlaces, int page, int totalPages, boolean visitsLimited, boolean readOnly,
            boolean visitsIncluded, String title, boolean placesLimited) {
        public OverviewResult {
            collections = List.copyOf(collections);
            places = List.copyOf(places);
            visits = List.copyOf(visits);
        }
    }

    public record TravelCandidateData(Long id, Long planId, String planName, String title, LocalDate date,
            String country, String region, Double latitude, Double longitude) {}
    public record CandidatePage(List<TravelCandidateData> content, Window window, long total) {
        public CandidatePage { content = List.copyOf(content); }
    }
}
