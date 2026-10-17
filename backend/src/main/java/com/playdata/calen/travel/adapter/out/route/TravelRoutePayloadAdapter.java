package com.playdata.calen.travel.adapter.out.route;

import static com.playdata.calen.travel.domain.policy.TravelRoutePolicy.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.application.port.out.TravelRoutePayloadPort;
import com.playdata.calen.travel.domain.TravelRouteSourceType;
import com.playdata.calen.travel.domain.model.TravelRouteData.*;
import com.playdata.calen.travel.domain.policy.TravelCoordinatePolicy;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import java.math.*;
import java.io.InputStream;
import java.util.*;
import javax.sql.DataSource;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;

@Component
@RequiredArgsConstructor
public class TravelRoutePayloadAdapter implements TravelRoutePayloadPort {
    private static final int DEFAULT_ROUTE_PATH_CHAR_BUDGET=12000, MIN_ROUTE_PATH_CHAR_BUDGET=220, ROUTE_PATH_PRECISION=6;
    private static final String ROUTE_PATH_FORMAT_POLYLINE6="POLYLINE6";
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;
    private final TravelMediaStorageService travelMediaStorageService;
    private volatile Integer routePathCharBudget;
    private record RoutePathPayload(String format,String encodedPath) {}
    private BigDecimal normalizeCoordinate(BigDecimal value,boolean latitude){return TravelCoordinatePolicy.normalize(value,latitude);}
    @Override public String encode(List<Point> points, TravelRouteSourceType sourceType) {
        try {
            if (sourceType != TravelRouteSourceType.GPX) {
                return objectMapper.writeValueAsString(points);
            }
            return objectMapper.writeValueAsString(new RoutePathPayload(
                    ROUTE_PATH_FORMAT_POLYLINE6,
                    encodeRoutePolyline(points)
            ));
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("Failed to save route points.");
        }
    }

    @Override public int characterBudget() {
        Integer cached = routePathCharBudget;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (routePathCharBudget != null) {
                return routePathCharBudget;
            }

            routePathCharBudget = inspectRoutePathCharBudget();
            return routePathCharBudget;
        }
    }

    private int inspectRoutePathCharBudget() {
        try (var connection = dataSource.getConnection()) {
            var metaData = connection.getMetaData();

            int budget = readRoutePathColumnBudget(metaData, connection.getCatalog(), "travel_route_segments", "route_path_json");
            if (budget > 0) {
                return budget;
            }

            budget = readRoutePathColumnBudget(metaData, connection.getCatalog(), "TRAVEL_ROUTE_SEGMENTS", "ROUTE_PATH_JSON");
            if (budget > 0) {
                return budget;
            }
        } catch (Exception ignored) {
            return DEFAULT_ROUTE_PATH_CHAR_BUDGET;
        }

        return DEFAULT_ROUTE_PATH_CHAR_BUDGET;
    }

    private int readRoutePathColumnBudget(
            java.sql.DatabaseMetaData metaData,
            String catalog,
            String tableName,
            String columnName
    ) throws java.sql.SQLException {
        try (var columns = metaData.getColumns(catalog, null, tableName, columnName)) {
            if (!columns.next()) {
                return -1;
            }

            int columnSize = columns.getInt("COLUMN_SIZE");
            String typeName = columns.getString("TYPE_NAME");
            if (typeName != null) {
                String normalizedType = typeName.trim().toUpperCase();
                if (normalizedType.contains("LONGTEXT") || normalizedType.contains("CLOB")) {
                    return DEFAULT_ROUTE_PATH_CHAR_BUDGET;
                }
                if (normalizedType.contains("TEXT") && columnSize >= DEFAULT_ROUTE_PATH_CHAR_BUDGET) {
                    return DEFAULT_ROUTE_PATH_CHAR_BUDGET;
                }
            }

            if (columnSize > 0) {
                return Math.max(MIN_ROUTE_PATH_CHAR_BUDGET, (int) Math.floor(columnSize * 0.9d));
            }
        }
        return -1;
    }

    @Override public String encodeFiles(List<GpxFile> files) {
        try {
            return objectMapper.writeValueAsString(files == null ? Collections.emptyList() : files);
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("Failed to save GPX file metadata.");
        }
    }

    @Override public List<GpxFile> files(String gpxFilesJson) {
        if (gpxFilesJson == null || gpxFilesJson.isBlank()) {
            return Collections.emptyList();
        }

        try {
            List<GpxFile> files = objectMapper.readValue(gpxFilesJson, new TypeReference<>() {
            });
            return files == null ? Collections.emptyList() : files.stream()
                    .filter(file -> file != null && file.storagePath() != null && !file.storagePath().isBlank())
                    .toList();
        } catch (JsonProcessingException exception) {
            return Collections.emptyList();
        }
    }

    @Override public void deleteFiles(String metadata) {
        files(metadata).forEach(file -> travelMediaStorageService.deleteQuietly(file.storagePath()));
    }

    @Override public List<Point> resolve(TravelRouteSourceType sourceType, String gpxFilesJson, String routePathJson) {
        if (sourceType == TravelRouteSourceType.GPX) {
            List<GpxFile> routeGpxFiles = files(gpxFilesJson);
            if (!routeGpxFiles.isEmpty()) {
                try {
                    List<Point> mergedPoints = new ArrayList<>();
                    for (GpxFile routeGpxFile : routeGpxFiles) {
                        Resource resource = travelMediaStorageService.loadAsResource(routeGpxFile.storagePath());
                        mergedPoints.addAll(parseGpxResource(resource));
                    }

                    List<Point> deduplicatedPoints = deduplicateRoutePoints(mergedPoints);
                    if (deduplicatedPoints.size() >= 2) {
                        return deduplicatedPoints.stream()
                                .map(this::toRoutePointResponse)
                                .toList();
                    }
                } catch (RuntimeException ignored) {
                    // Fall back to the reduced path snapshot if the original GPX file is unavailable.
                }
            }
        }

        return decode(routePathJson);
    }

    private List<Point> parseGpxResource(Resource resource) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            try (InputStream inputStream = resource.getInputStream()) {
                var documentBuilder = factory.newDocumentBuilder();
                var document = documentBuilder.parse(inputStream);
                List<Point> trackPoints = extractGpxPoints(document, "trkpt");
                if (trackPoints.size() >= 2) {
                    return trackPoints;
                }
                return extractGpxPoints(document, "rtept");
            }
        } catch (Exception exception) {
            throw new BadRequestException("Failed to read GPX route file.");
        }
    }

    private List<Point> extractGpxPoints(org.w3c.dom.Document document, String tagName) {
        NodeList pointNodes = document.getElementsByTagName(tagName);
        List<Point> points = new ArrayList<>();

        for (int index = 0; index < pointNodes.getLength(); index += 1) {
            if (!(pointNodes.item(index) instanceof Element element)) {
                continue;
            }

            String latitudeText = trimToNull(element.getAttribute("lat"));
            String longitudeText = trimToNull(element.getAttribute("lon"));
            if (latitudeText == null || longitudeText == null) {
                continue;
            }

            try {
                points.add(new Point(
                        normalizeCoordinate(new BigDecimal(latitudeText), true),
                        normalizeCoordinate(new BigDecimal(longitudeText), false)
                ));
            } catch (NumberFormatException ignored) {
                // Skip invalid GPX coordinates and keep parsing the remaining track.
            }
        }

        return points;
    }

    @Override public List<Point> decode(String routePathJson) {
        if (routePathJson == null || routePathJson.isBlank()) {
            return Collections.emptyList();
        }

        try {
            String trimmed = routePathJson.trim();
            if (trimmed.startsWith("[")) {
                List<Point> points = objectMapper.readValue(trimmed, new TypeReference<>() {
                });
                return points.stream()
                        .map(this::toRoutePointResponse)
                        .toList();
            }

            RoutePathPayload payload = objectMapper.readValue(trimmed, RoutePathPayload.class);
            if (payload == null || payload.encodedPath() == null || payload.encodedPath().isBlank()) {
                return Collections.emptyList();
            }

            if (!ROUTE_PATH_FORMAT_POLYLINE6.equalsIgnoreCase(String.valueOf(payload.format()))) {
                throw new BadRequestException("Failed to read saved route points.");
            }

            return decodeRoutePolyline(payload.encodedPath()).stream()
                    .map(this::toRoutePointResponse)
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("Failed to read saved route points.");
        }
    }

    private Point toRoutePointResponse(Point point) {
        return new Point(
                point.latitude(),
                point.longitude(),
                normalizeRoutePointType(point.pointType()),
                point.linkedMemoryId(),
                trimRoutePointLabel(point.label())
        );
    }

    private String encodeRoutePolyline(List<Point> points) {
        StringBuilder encoded = new StringBuilder();
        long previousLatitude = 0L;
        long previousLongitude = 0L;

        for (Point point : points) {
            long latitude = scaleRouteCoordinate(point.latitude());
            long longitude = scaleRouteCoordinate(point.longitude());
            appendEncodedDifference(encoded, latitude - previousLatitude);
            appendEncodedDifference(encoded, longitude - previousLongitude);
            previousLatitude = latitude;
            previousLongitude = longitude;
        }

        return encoded.toString();
    }

    private List<Point> decodeRoutePolyline(String encodedPath) {
        List<Point> points = new ArrayList<>();
        int index = 0;
        long latitude = 0L;
        long longitude = 0L;

        while (index < encodedPath.length()) {
            DecodeStep latitudeStep = decodePolylineValue(encodedPath, index);
            index = latitudeStep.nextIndex();
            latitude += latitudeStep.delta();

            if (index >= encodedPath.length()) {
                break;
            }

            DecodeStep longitudeStep = decodePolylineValue(encodedPath, index);
            index = longitudeStep.nextIndex();
            longitude += longitudeStep.delta();

            points.add(new Point(
                    BigDecimal.valueOf(latitude, ROUTE_PATH_PRECISION).setScale(7, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(longitude, ROUTE_PATH_PRECISION).setScale(7, RoundingMode.HALF_UP)
            ));
        }

        return List.copyOf(points);
    }

    private long scaleRouteCoordinate(BigDecimal coordinate) {
        return coordinate
                .movePointRight(ROUTE_PATH_PRECISION)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    private void appendEncodedDifference(StringBuilder builder, long difference) {
        long value = difference < 0 ? ~(difference << 1) : (difference << 1);
        while (value >= 0x20) {
            builder.append((char) ((0x20 | (value & 0x1f)) + 63));
            value >>= 5;
        }
        builder.append((char) (value + 63));
    }

    private DecodeStep decodePolylineValue(String encodedPath, int startIndex) {
        long result = 0L;
        int shift = 0;
        int index = startIndex;
        int chunk;

        do {
            chunk = encodedPath.charAt(index++) - 63;
            result |= (long) (chunk & 0x1f) << shift;
            shift += 5;
        } while (chunk >= 0x20 && index < encodedPath.length());

        long delta = (result & 1L) != 0L ? ~(result >> 1) : (result >> 1);
        return new DecodeStep(delta, index);
    }

    private record DecodeStep(
            long delta,
            int nextIndex
    ) {
    }
}
