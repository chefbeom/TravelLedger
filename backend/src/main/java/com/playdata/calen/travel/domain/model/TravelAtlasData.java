package com.playdata.calen.travel.domain.model;

import com.playdata.calen.travel.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Detached atlas/feed values. HTTP addresses are composed only by the inbound mapper. */
public final class TravelAtlasData {
    private TravelAtlasData() {}
    public record Owner(String login, String display) {}
    public record Plan(Long id, String name, String destination, LocalDate start, LocalDate end,
            TravelPlanStatus status, String color, Owner owner, Boolean publicShared, LocalDateTime publicAt) {}
    public record Access(Plan plan, Long share, LocalDateTime sharedAt) {}
    public record Memory(TravelOwnedMapData.Marker marker, TravelRecordType type, String memo) {}
    public record Photo(Long id, Plan plan, TravelPhotoClusterData.PhotoPoint point) {}
    public record FeedPhoto(Long id, Long recordId, LocalDateTime uploadedAt) {}
    public record Hero(String uploadedBy, String caption) {}
    public record Window(List<Memory> items, long total, int totalPages) {}
    public record Reference(Long media, String token, Long share) {}
    public record Sharing(String login, String display, LocalDateTime at, String visibility, Long share) {}
    public record Trip(Plan plan, Sharing sharing, int memories, int photos, int routes, BigDecimal distance, Reference hero) {}
    public record Summary(TravelPhotoClusterData.Summary value, Reference photo, Sharing sharing) {}
    public record Pin(TravelPhotoClusterData.Pin value, Reference photo, Sharing sharing) {}
    public record Overview(int photos, BigDecimal distance, List<Trip> plans, List<Summary> clusters, List<Pin> pins, List<TravelRouteData.Result> routes) {}
    public record Cluster(TravelClusterDetailData.Page page, Map<Long, Reference> references) {}
    public record Post(Memory memory, String color, Hero hero, Reference photo, int photoCount) {}
    public record Feed(List<Post> items, int page, int size, long total, int totalPages) {}
}
