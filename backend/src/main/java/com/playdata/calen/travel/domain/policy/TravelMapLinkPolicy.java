package com.playdata.calen.travel.domain.policy;

import com.playdata.calen.travel.domain.*;
import com.playdata.calen.travel.domain.model.TravelMapLinkData.*;
import com.playdata.calen.travel.domain.model.TravelRouteData.Point;
import java.math.*;
import java.util.*;

public final class TravelMapLinkPolicy {
    private TravelMapLinkPolicy() {}
    public static final int PREVIEW_MARKERS = 80, PREVIEW_ROUTES = 12, PREVIEW_POINTS = 160;
    public static List<Long> ids(Collection<Long> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().filter(Objects::nonNull).filter(id -> id > 0)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)).stream().toList();
    }
    public static String text(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    public static String title(String value) { var result = text(value); return result == null ? null : result.substring(0, Math.min(160, result.length())); }
    public static boolean visible(Asset asset, Set<Long> plans, Scope scope) {
        return asset != null && asset.planId() != null && asset.recordId() != null && plans.contains(asset.planId())
                && asset.mediaType() == TravelMediaType.PHOTO && asset.recordType() == TravelRecordType.MEMORY
                && !scope.records().contains(asset.recordId()) && !scope.media().contains(asset.id())
                && (asset.recordCoordinates() || asset.clusterCoordinates());
    }
    public static BigDecimal rounded(BigDecimal value) { return value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros(); }
    public static List<PreviewPoint> sample(List<Point> source) {
        var valid = source.stream().filter(point -> point != null && point.latitude() != null && point.longitude() != null).toList();
        if (valid.size() < 2) return List.of();
        int stride = Math.max(1, (int) Math.ceil((double) valid.size() / PREVIEW_POINTS));
        var result = new ArrayList<PreviewPoint>();
        for (int i = 0; i < valid.size(); i += stride) result.add(rounded(valid.get(i)));
        var last = rounded(valid.get(valid.size() - 1));
        if (!result.get(result.size() - 1).equals(last)) result.add(last);
        return result;
    }
    private static PreviewPoint rounded(Point value) { return new PreviewPoint(rounded(value.latitude()), rounded(value.longitude())); }
}
