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
                .queryParam("limit", "6").queryParam("q", query).build().encode().toUri();
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
        for (JsonNode row : data) {
            if (result.size() == 6) break;
            double lat = row.path("lat").asDouble(Double.NaN), lon = row.path("lon").asDouble(Double.NaN);
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat)>90 || Math.abs(lon)>180) continue;
            JsonNode address = row.path("address");
            String display = text(row, "display_name", 500);
            String name = text(row, "name", 160);
            if (name.isBlank()) name = display.substring(0, Math.min(160, display.length()));
            if (name.isBlank()) continue;
            String region = text(address, "city", 120);
            if (region.isBlank()) region = text(address, "town", 120);
            if (region.isBlank()) region = text(address, "state", 120);
            result.add(new LocationView(name, display, text(address, "country", 80), region, lat, lon));
        }
        return result;
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
