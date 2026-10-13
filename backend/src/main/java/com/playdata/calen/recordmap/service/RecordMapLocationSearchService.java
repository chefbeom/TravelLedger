package com.playdata.calen.recordmap.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.common.cache.BoundedExpiringCache;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.common.jobs.WorkLeaseService;
import com.playdata.calen.recordmap.dto.RecordMapDtos.LocationView;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Explicit address lookups only. No owner records/memos are sent upstream. */
@Service
public class RecordMapLocationSearchService {
    private static final int MAX_RESULTS = 10;
    private final RestClient client;
    private final ObjectMapper json;
    private final WorkLeaseService leases;
    private final URI endpoint;
    private final Duration interval;
    private final Semaphore admission = new Semaphore(1);
    private final BoundedExpiringCache<String, List<LocationView>> cache = new BoundedExpiringCache<>(256);

    public RecordMapLocationSearchService(RestClient.Builder builder, ObjectMapper json, WorkLeaseService leases,
            @Value("${app.travel.reverse-geocode-base-url}") String provider,
            @Value("${app.travel.reverse-geocode-user-agent}") String userAgent,
            @Value("${app.travel.reverse-geocode-request-min-interval-ms:1200}") long intervalMillis) {
        this.json = json; this.leases = leases;
        URI base = URI.create(provider);
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null) throw new IllegalArgumentException("Invalid geocoding provider");
        String path = base.getPath();
        path = path.endsWith("/reverse") ? path.substring(0, path.length()-7) + "search" : path.replaceAll("/?$", "/") + "search";
        endpoint = UriComponentsBuilder.fromUri(base).replacePath(path).replaceQuery(null).fragment(null).build().toUri();
        interval = Duration.ofMillis(Math.max(1000, intervalMillis));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3)); factory.setReadTimeout(Duration.ofSeconds(5));
        client = builder.requestFactory(factory).defaultHeader("User-Agent", userAgent).defaultHeader("Accept-Language", "ko,en").build();
    }

    public List<LocationView> search(String input) {
        String query = input == null ? "" : input.trim();
        if (query.length() < 2 || query.length() > 100 || query.chars().anyMatch(Character::isISOControl)) throw new BadRequestException("주소·지역 검색어를 2~100자로 입력해 주세요.");
        String key = digest(query.toLowerCase(Locale.ROOT));
        List<LocationView> cached = cache.get(key);
        if (cached != null) return cached;
        if (!admission.tryAcquire()) throw new ServiceUnavailableException("다른 위치 검색을 처리 중입니다. 잠시 후 다시 검색해 주세요.");
        try {
            cached = cache.get(key);
            if (cached != null) return cached;
            // Same DB-backed provider key as reverse geocoding, across all backend instances.
            if (!leases.tryReserveInterval("geocode-provider:" + endpoint.getHost(), interval)) throw new ServiceUnavailableException("위치 검색 간격을 두고 다시 검색해 주세요.");
            URI uri = UriComponentsBuilder.fromUri(endpoint).queryParam("format", "jsonv2").queryParam("addressdetails", "1")
                .queryParam("namedetails", "1").queryParam("entrances", "1")
                .queryParam("limit", MAX_RESULTS).queryParam("q", query).build().encode().toUri();
            List<LocationView> result = client.get().uri(uri).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) throw new ServiceUnavailableException("위치 검색 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
                byte[] body = response.getBody().readNBytes(65537);
                if (body.length > 65536) throw new ServiceUnavailableException("위치 검색 응답이 너무 큽니다.");
                return parse(json.readTree(body));
            });
            result = List.copyOf(result == null ? List.of() : result);
            cache.put(key, result, Duration.ofHours(24));
            return result;
        } catch (ServiceUnavailableException e) { throw e; }
        catch (Exception e) { throw new ServiceUnavailableException("위치 검색을 처리하지 못했습니다. 잠시 후 다시 검색해 주세요."); }
        finally { admission.release(); }
    }

    private List<LocationView> parse(JsonNode data) {
        if (data == null || !data.isArray()) return List.of();
        List<LocationView> result = new ArrayList<>();
        List<LocationView> entrances = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> seenEntrances = new HashSet<>();
        for (JsonNode row : data) {
            if (result.size() == MAX_RESULTS) break;
            double lat = row.path("lat").asDouble(Double.NaN), lon = row.path("lon").asDouble(Double.NaN);
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat)>90 || Math.abs(lon)>180) continue;
            JsonNode address = row.path("address");
            String display = text(row, "display_name", 500);
            String name = text(row, "name", 160);
            if (name.isBlank()) name = firstText(row.path("namedetails"), 160, "name:ko", "name", "official_name");
            if (name.isBlank()) name = display.substring(0, Math.min(160, display.length()));
            if (name.isBlank()) continue;
            String region = text(address, "city", 120);
            if (region.isBlank()) region = text(address, "town", 120);
            if (region.isBlank()) region = text(address, "state", 120);
            String country = text(address, "country", 80);
            addUnique(result, seen, new LocationView(name, display, country, region, lat, lon));
            // Only offer entrances that the provider actually returned. Never
            // synthesize numbered exits or reuse the station centroid for them.
            if (row.path("entrances").isArray()) for (JsonNode entrance : row.path("entrances")) {
                if (entrances.size() == MAX_RESULTS) break;
                String type = text(entrance, "type", 30);
                JsonNode tags = entrance.path("extratags");
                if (Set.of("service", "emergency", "no").contains(type)
                    || Set.of("private", "no", "delivery").contains(text(tags, "access", 30))) continue;
                double entranceLat = entrance.path("lat").asDouble(Double.NaN), entranceLon = entrance.path("lon").asDouble(Double.NaN);
                if (!Double.isFinite(entranceLat) || !Double.isFinite(entranceLon) || Math.abs(entranceLat)>90 || Math.abs(entranceLon)>180) continue;
                String entranceName = firstText(tags, 160, "name:ko", "name");
                if (entranceName.isBlank()) {
                    String ref = firstText(tags, 30, "ref", "local_ref");
                    String suffix = ref.matches("[0-9]{1,3}") ? ref + "번 출구"
                        : !ref.isBlank() ? ref + " 출입구" : "main".equals(type) ? "정문" : "exit".equals(type) ? "출구" : "출입구";
                    entranceName = (name + " " + suffix).substring(0, Math.min(160, name.length() + 1 + suffix.length()));
                }
                addUnique(entrances, seenEntrances, new LocationView(entranceName, entranceName + " · " + display, country, region, entranceLat, entranceLon));
            }
        }
        for (LocationView entrance : entrances) { if (result.size() == MAX_RESULTS) break; addUnique(result, seen, entrance); }
        return result;
    }
    private void addUnique(List<LocationView> result, Set<String> seen, LocationView row) {
        String key = row.name().toLowerCase(Locale.ROOT) + ":" + row.latitude() + ":" + row.longitude();
        if (seen.add(key)) result.add(row);
    }
    private String firstText(JsonNode row, int max, String... fields) {
        for (String field : fields) { String value = text(row, field, max); if (!value.isBlank()) return value; }
        return "";
    }
    private String text(JsonNode row, String field, int max) {
        String value = row.path(field).asText("").strip();
        return value.substring(0, Math.min(max, value.length()));
    }
    private String digest(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
