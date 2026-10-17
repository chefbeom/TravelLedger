package com.playdata.calen.recordmap.application.service;

import static com.playdata.calen.recordmap.domain.policy.RecordMapReadPolicy.*;

import com.playdata.calen.recordmap.application.port.in.ReadRecordMapUseCase;
import com.playdata.calen.recordmap.application.port.out.RecordMapQueryPort;
import com.playdata.calen.recordmap.application.port.out.RecordMapReadAccessPort;
import com.playdata.calen.recordmap.domain.model.RecordMapReadData.*;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ReadRecordMapService implements ReadRecordMapUseCase {
    private final RecordMapReadAccessPort access;
    private final RecordMapQueryPort query;
    private final Clock clock;

    public ReadRecordMapService(RecordMapReadAccessPort access, RecordMapQueryPort query, Clock clock) {
        this.access = access;
        this.query = query;
        this.clock = clock;
    }

    @Override public OverviewResult owned(Long owner, Long collectionId, Filter filter) {
        return overview(access.owned(owner, collectionId), filter);
    }

    @Override public OverviewResult shared(String token, Filter filter) {
        return overview(access.shared(token), filter);
    }

    private OverviewResult overview(Scope scope, Filter filter) {
        Selection selection = select(scope, filter);
        Long owner = scope.ownerId();
        var page = query.places(scope, selection, privateFields(scope));
        var entries = page.content();
        var ids = entries.stream().map(PlaceData::id).toList();
        Map<Long, Long> counts = ids.isEmpty() ? Map.of() : query.visitCounts(owner, ids);
        List<VisitData> rows = !scope.includeVisits() || ids.isEmpty() ? List.of()
                : query.visits(owner, ids, selection.from(), selection.to(), OVERVIEW_VISITS + 1);
        boolean limited = rows.size() > OVERVIEW_VISITS;
        rows = rows.stream().filter(v -> inRange(v.date(), selection.from(), selection.to())).limit(OVERVIEW_VISITS).toList();
        List<VisitResult> visits = new ArrayList<>(visitResults(owner, rows));

        var sources = entries.stream().map(PlaceData::sourceTravelRecordId).filter(Objects::nonNull).toList();
        Map<Long, TravelRecordData> originals = sources.isEmpty() ? Map.of() : query.travelRecords(owner, sources);
        if (scope.includeVisits() && !sources.isEmpty()) {
            var photos = query.travelPhotos(owner, sources, TRAVEL_PHOTOS);
            limited |= photos.size() == TRAVEL_PHOTOS;
            var grouped = photos.stream().collect(Collectors.groupingBy(TravelPhoto::recordId,
                    Collectors.mapping(TravelPhoto::photo, Collectors.toList())));
            for (var place : entries) {
                var original = place.sourceTravelRecordId() == null ? null : originals.get(place.sourceTravelRecordId());
                if (original == null || !inRange(original.date(), selection.from(), selection.to())) continue;
                visits.add(new VisitResult(original.id(), Origin.TRAVEL, place.id(), original.title(), original.memo(),
                        original.date(), original.time(), original.planId(), original.planName(), grouped.getOrDefault(original.id(), List.of())));
            }
        }
        var places = entries.stream().map(p -> place(scope, p, counts.getOrDefault(p.id(), 0L)
                + (p.sourceTravelRecordId() != null && originals.containsKey(p.sourceTravelRecordId()) ? 1 : 0))).toList();
        var collections = collectionResults(scope, page.total());
        return new OverviewResult(collections, places, visits, page.total(), selection.window().page(), page.totalPages(),
                limited, scope.readOnly(), scope.includeVisits(), scope.readOnly() ? collections.get(0).name() : "기록 지도",
                filter.map() && page.total() > MAP_PLACES);
    }

    private List<CollectionResult> collectionResults(Scope scope, long total) {
        if (scope.readOnly()) {
            var collection = query.collection(scope.ownerId(), scope.collectionId());
            return List.of(new CollectionResult(collection.id(), collection.name(), null, collection.colorHex(), total, true));
        }
        var counts = query.collectionCounts(scope.ownerId());
        var shared = query.sharedCollections(scope.ownerId(), LocalDateTime.now(clock));
        return query.collections(scope.ownerId()).stream().map(c -> new CollectionResult(c.id(), c.name(), c.memo(),
                c.colorHex(), counts.getOrDefault(c.id(), 0L), shared.contains(c.id()))).toList();
    }

    @Override public List<VisitResult> placeVisits(Long owner, Long placeId, int page) {
        access.requireOwnedPlace(owner, placeId);
        return visitResults(owner, query.placeVisits(owner, placeId, new Window(Math.max(0, page), PLACE_VISITS_PAGE_SIZE)));
    }

    private List<VisitResult> visitResults(Long owner, List<VisitData> rows) {
        var ids = rows.stream().map(VisitData::id).toList();
        Map<Long, List<PhotoData>> photos = ids.isEmpty() ? Map.of() : query.visitPhotos(owner, ids);
        return rows.stream().map(v -> new VisitResult(v.id(), Origin.RECORD_MAP, v.placeId(), v.title(), v.memo(),
                v.date(), v.time(), null, null, photos.getOrDefault(v.id(), List.of()))).toList();
    }

    @Override public CandidatePage travelCandidates(Long owner, int page) {
        access.owned(owner, null);
        return query.travelCandidates(owner, new Window(Math.max(0, page), TRAVEL_CANDIDATES_PAGE_SIZE));
    }
}
