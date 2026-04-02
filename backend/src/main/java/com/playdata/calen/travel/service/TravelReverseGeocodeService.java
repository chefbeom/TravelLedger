package com.playdata.calen.travel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playdata.calen.travel.dto.TravelReverseGeocodeResponse;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class TravelReverseGeocodeService {

    private static final String CACHE_KEY_PREFIX = "geo:reverse:";

    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    @Value("${app.travel.reverse-geocode-base-url}")
    private String reverseGeocodeBaseUrl;

    @Value("${app.travel.reverse-geocode-user-agent}")
    private String reverseGeocodeUserAgent;

    @Value("${app.travel.reverse-geocode-request-min-interval-ms:1200}")
    private long reverseGeocodeRequestMinIntervalMs;

    @Value("${app.travel.reverse-geocode-cache-ttl-hours:720}")
    private long reverseGeocodeCacheTtlHours;

    @Value("${app.redis.cache.host:}")
    private String redisCacheHost;

    @Value("${app.redis.cache.port:6379}")
    private int redisCachePort;

    @Value("${app.redis.cache.password:}")
    private String redisCachePassword;

    @Value("${app.redis.cache.database:0}")
    private int redisCacheDatabase;

    @Value("${app.redis.cache.ssl:false}")
    private boolean redisCacheSsl;

    private RestClient restClient;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> redisConnection;
    private RedisCommands<String, String> redisCommands;
    private long lastProviderRequestAt = 0L;

    @PostConstruct
    void initialize() {
        restClient = restClientBuilder
                .defaultHeader("User-Agent", reverseGeocodeUserAgent)
                .defaultHeader("Accept-Language", "ko,en")
                .build();

        if (!StringUtils.hasText(redisCacheHost)) {
            return;
        }

        RedisURI.Builder redisUriBuilder = RedisURI.builder()
                .withHost(redisCacheHost.trim())
                .withPort(redisCachePort)
                .withDatabase(redisCacheDatabase)
                .withTimeout(Duration.ofSeconds(3));

        if (StringUtils.hasText(redisCachePassword)) {
            redisUriBuilder.withPassword(redisCachePassword.toCharArray());
        }
        if (redisCacheSsl) {
            redisUriBuilder.withSsl(true);
        }

        redisClient = RedisClient.create(redisUriBuilder.build());
        redisConnection = redisClient.connect();
        redisCommands = redisConnection.sync();
    }

    @PreDestroy
    void shutdown() {
        if (redisConnection != null) {
            redisConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    public TravelReverseGeocodeResponse reverseGeocode(double latitude, double longitude) {
        String cacheKey = buildCacheKey(latitude, longitude);
        TravelReverseGeocodeResponse cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        TravelReverseGeocodeResponse resolved = fetchFromProvider(latitude, longitude);
        writeCache(cacheKey, resolved);
        return resolved;
    }

    private TravelReverseGeocodeResponse readCache(String cacheKey) {
        if (redisCommands == null) {
            return null;
        }

        try {
            String cachedValue = redisCommands.get(cacheKey);
            if (!StringUtils.hasText(cachedValue)) {
                return null;
            }
            return objectMapper.readValue(cachedValue, TravelReverseGeocodeResponse.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeCache(String cacheKey, TravelReverseGeocodeResponse response) {
        if (redisCommands == null) {
            return;
        }

        try {
            long ttlSeconds = Math.max(60L, reverseGeocodeCacheTtlHours * 3600L);
            redisCommands.setex(cacheKey, ttlSeconds, objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException ignored) {
            // Ignore cache serialization failures.
        } catch (Exception ignored) {
            // Ignore cache write failures and keep the provider response.
        }
    }

    private TravelReverseGeocodeResponse fetchFromProvider(double latitude, double longitude) {
        try {
            waitForProviderSlot();
            JsonNode payload = restClient.get()
                    .uri(reverseGeocodeBaseUrl + "?format=jsonv2&lat=" + latitude + "&lon=" + longitude + "&zoom=18&addressdetails=1")
                    .retrieve()
                    .body(JsonNode.class);

            if (payload == null) {
                return TravelReverseGeocodeResponse.empty();
            }

            JsonNode address = payload.path("address");
            String country = textOrEmpty(address, "country");
            String region = firstNonBlank(
                    textOrEmpty(address, "city"),
                    textOrEmpty(address, "town"),
                    textOrEmpty(address, "village"),
                    textOrEmpty(address, "municipality"),
                    textOrEmpty(address, "county"),
                    textOrEmpty(address, "state_district"),
                    textOrEmpty(address, "state")
            );
            String placeName = firstNonBlank(
                    textOrEmpty(payload, "name"),
                    textOrEmpty(address, "attraction"),
                    textOrEmpty(address, "amenity"),
                    textOrEmpty(address, "shop"),
                    textOrEmpty(address, "tourism"),
                    textOrEmpty(address, "leisure"),
                    textOrEmpty(address, "building"),
                    textOrEmpty(address, "road"),
                    textOrEmpty(address, "neighbourhood"),
                    textOrEmpty(address, "suburb")
            );

            return new TravelReverseGeocodeResponse(country, region, placeName);
        } catch (Exception ignored) {
            return TravelReverseGeocodeResponse.empty();
        }
    }

    private synchronized void waitForProviderSlot() throws InterruptedException {
        if (reverseGeocodeRequestMinIntervalMs <= 0) {
            lastProviderRequestAt = System.currentTimeMillis();
            return;
        }

        long elapsed = System.currentTimeMillis() - lastProviderRequestAt;
        long waitMs = reverseGeocodeRequestMinIntervalMs - elapsed;
        if (waitMs > 0) {
            Thread.sleep(waitMs);
        }
        lastProviderRequestAt = System.currentTimeMillis();
    }

    private String buildCacheKey(double latitude, double longitude) {
        return CACHE_KEY_PREFIX
                + String.format(Locale.US, "%.5f", latitude)
                + ":"
                + String.format(Locale.US, "%.5f", longitude);
    }

    private String textOrEmpty(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        return StringUtils.trimWhitespace(node.path(fieldName).asText(""));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }
}
