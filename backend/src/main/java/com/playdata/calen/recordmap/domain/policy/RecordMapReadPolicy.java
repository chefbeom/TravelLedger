package com.playdata.calen.recordmap.domain.policy;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.recordmap.domain.model.RecordMapReadData.*;
import java.time.LocalDate;
import java.util.Set;

public final class RecordMapReadPolicy {
    public static final int MAP_PLACES = 500;
    public static final int OVERVIEW_VISITS = 200;
    public static final int TRAVEL_PHOTOS = 1000;
    public static final int PLACE_VISITS_PAGE_SIZE = 20;
    public static final int TRAVEL_CANDIDATES_PAGE_SIZE = 30;

    private RecordMapReadPolicy() {}

    public static Selection select(Scope scope, Filter filter) {
        // Preserve validation order: hidden visits reject date queries before general filters.
        if (!scope.includeVisits() && (filter.from() != null || filter.to() != null))
            throw new BadRequestException("방문 기록을 공유하지 않은 지도에서는 날짜를 조회할 수 없습니다.");
        if (!Set.of("ALL", "WANTED", "VISITED").contains(filter.state()))
            throw new BadRequestException("방문 상태를 확인해 주세요.");
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to()))
            throw new BadRequestException("조회 시작일은 종료일보다 늦을 수 없습니다.");
        if (filter.page() < 0) throw new BadRequestException("페이지를 확인해 주세요.");
        if (filter.size() < 1 || filter.size() > 100) throw new BadRequestException("목록 크기는 1~100개로 선택해 주세요.");
        String keyword = filter.keyword() == null ? "" : filter.keyword().trim();
        if (keyword.length() > 100) throw new BadRequestException("검색어는 100자 이하로 입력해 주세요.");
        return new Selection(filter.state(), filter.from(), filter.to(), keyword,
                new Window(filter.map() ? 0 : filter.page(), filter.map() ? MAP_PLACES : filter.size()));
    }

    public static boolean inRange(LocalDate date, LocalDate from, LocalDate to) {
        return date != null && (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }

    public static boolean privateFields(Scope scope) { return !scope.readOnly() || scope.includeVisits(); }

    public static PlaceResult place(Scope scope, PlaceData place, long count) {
        return new PlaceResult(place.id(), place.name(), privateFields(scope) ? place.memo() : null,
                place.country(), place.region(), place.latitude(), place.longitude(), place.manuallyVisited() || count > 0,
                scope.includeVisits() ? count : 0, scope.readOnly() ? null : place.sourceTravelRecordId(),
                place.collectionIds().stream().filter(id -> !scope.readOnly() || id.equals(scope.collectionId())).toList());
    }
}
