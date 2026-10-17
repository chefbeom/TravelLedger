package com.playdata.calen.recordmap.domain.policy;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.recordmap.domain.model.RecordMapWriteData.*;
import java.util.Objects;

public final class RecordMapWritePolicy {
    private RecordMapWritePolicy() {}

    public static void validateCoordinates(Double latitude, Double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude))
            throw new BadRequestException("위치 좌표가 올바르지 않습니다.");
    }

    public static void requireNoVisits(long count) {
        if (count > 0) throw new BadRequestException("방문 기록이 있는 장소는 삭제할 수 없습니다. 모음에서 제거하거나 방문 기록을 먼저 정리해 주세요.");
    }

    public static void requireSamePlace(Long existingPlace, Long requestedPlace) {
        if (!Objects.equals(existingPlace, requestedPlace))
            throw new NotFoundException("기록 지도 또는 공유 자료를 찾을 수 없습니다.");
    }

    public static PlaceDraft importedPlace(TravelSource source) {
        if (source.latitude() == null || source.longitude() == null)
            throw new BadRequestException("위치가 있는 여행 기록을 선택해 주세요.");
        double latitude = source.latitude(), longitude = source.longitude();
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180)
            throw new BadRequestException("여행 원본의 위치 좌표를 먼저 확인해 주세요.");
        String name = source.placeName() == null || source.placeName().isBlank() ? source.title() : source.placeName();
        return new PlaceDraft(limit(name, 160), null, limit(source.country(), 80), limit(source.region(), 120),
                latitude, longitude, false, null);
    }

    private static String limit(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
