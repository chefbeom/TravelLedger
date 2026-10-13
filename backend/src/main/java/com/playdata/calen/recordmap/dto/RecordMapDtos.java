package com.playdata.calen.recordmap.dto;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;

public final class RecordMapDtos {
    private RecordMapDtos() {}
    public record PlaceRequest(@NotBlank @Size(max=160) String name, @Size(max=2000) String memo,
        @Size(max=80) String country, @Size(max=120) String region,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        boolean visited, @Size(max=100) List<@NotNull @Positive Long> collectionIds) {}
    public record CollectionRequest(@NotBlank @Size(max=100) String name, @Size(max=1000) String memo,
        @NotNull @Pattern(regexp="^#[0-9A-Fa-f]{6}$") String colorHex) {}
    public record VisitRequest(@NotBlank @Size(max=160) String title, @Size(max=4000) String memo,
        @NotNull LocalDate date, LocalTime time) {}
    public record TravelImportRequest(@NotNull @Positive Long recordId, @Size(max=100) List<@NotNull @Positive Long> collectionIds) {}
    public record ShareRequest(boolean includeVisits, @Min(1) @Max(365) int expiresInDays) {}
    public record CollectionView(Long id, String name, String memo, String colorHex) {}
    public record PlaceView(Long id, String name, String memo, String country, String region, Double latitude,
        Double longitude, boolean visited, long visitCount, Long sourceTravelRecordId, List<Long> collectionIds) {}
    public record PhotoView(String id, String name, String photoUrl) {}
    public record VisitView(String id, Long placeId, String title, String memo, LocalDate date, LocalTime time,
        Long travelPlanId, String travelPlanName, List<PhotoView> photos) {}
    public record Overview(List<CollectionView> collections, List<PlaceView> places, List<VisitView> visits,
        long totalPlaces, int page, int totalPages, boolean visitsLimited, boolean readOnly, boolean visitsIncluded, String title) {}
    public record TravelCandidate(Long id, Long planId, String planName, String title, LocalDate date,
        String country, String region, Double latitude, Double longitude) {}
    public record ShareView(Long id, boolean active, boolean includeVisits, LocalDateTime expiresAt, String token) {}
}
