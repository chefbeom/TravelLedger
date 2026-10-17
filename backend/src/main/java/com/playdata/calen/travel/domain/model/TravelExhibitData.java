package com.playdata.calen.travel.domain.model;

import com.playdata.calen.travel.domain.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class TravelExhibitData {
    private TravelExhibitData() {}
    public record Plan(Long id, Long ownerId, TravelPlanStatus status) {}
    public record Access(Long id, Plan plan) {}
    public record Attribution(String loginId, String displayName, LocalDateTime sharedAt) {}
    public record Summary(Long id, Long planId, String planName, String destination, LocalDate startDate, LocalDate endDate,
            TravelPlanStatus status, String colorHex, Attribution sharedBy, int memoryRecordCount, int routeSegmentCount, int mediaItemCount) {}
    public record Page(List<Summary> items, int page, int size, long totalElements, int totalPages) {}
    public record Detail(Long id, Attribution sharedBy, TravelPlanDetailData.Detail plan) {}
    public record Media(Long planId, TravelMediaData.Download download) {}
    public record PublicMedia(TravelRecordType recordType, TravelMediaType mediaType, boolean community,
            boolean publicPlan, TravelPlanStatus status, TravelMediaData.Download download) {}
}
