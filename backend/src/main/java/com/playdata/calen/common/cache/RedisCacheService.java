package com.playdata.calen.common.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class RedisCacheService {

    private static final long REDIS_RECONNECT_MIN_BACKOFF_MS = 30_000L;
    private static final long REDIS_RECONNECT_MAX_BACKOFF_MS = 300_000L;

    private final ObjectMapper objectMapper;
    private final Object redisMonitor = new Object();

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

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> redisConnection;
    private RedisCommands<String, String> redisCommands;
    private long nextRedisReconnectAt = 0L;
    private int redisReconnectFailures = 0;

    @PostConstruct
    void initialize() {
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
        tryConnectRedis(false);
    }

    @PreDestroy
    void shutdown() {
        closeRedisConnection();
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    public <T> T get(String key, Class<T> valueType) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return null;
        }

        try {
            String cachedValue = commands.get(key);
            if (!StringUtils.hasText(cachedValue)) {
                return null;
            }
            return objectMapper.readValue(cachedValue, valueType);
        } catch (Exception ignored) {
            markRedisUnavailable();
            return null;
        }
    }

    public <T> T get(String key, TypeReference<T> valueType) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return null;
        }

        try {
            String cachedValue = commands.get(key);
            if (!StringUtils.hasText(cachedValue)) {
                return null;
            }
            return objectMapper.readValue(cachedValue, valueType);
        } catch (Exception ignored) {
            markRedisUnavailable();
            return null;
        }
    }

    public void set(String key, Object value, Duration ttl) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }

        try {
            commands.setex(key, Math.max(1L, ttl.getSeconds()), objectMapper.writeValueAsString(value));
        } catch (Exception ignored) {
            markRedisUnavailable();
        }
    }

    public long delete(String... keys) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null || keys == null || keys.length == 0) {
            return -1L;
        }

        try {
            return commands.del(keys);
        } catch (Exception ignored) {
            markRedisUnavailable();
            return -1L;
        }
    }

    private RedisCommands<String, String> ensureRedisCommands() {
        synchronized (redisMonitor) {
            if (redisCommands != null && redisConnection != null && redisConnection.isOpen()) {
                return redisCommands;
            }
        }

        tryConnectRedis(false);

        synchronized (redisMonitor) {
            if (redisCommands != null && redisConnection != null && redisConnection.isOpen()) {
                return redisCommands;
            }
            return null;
        }
    }

    private void tryConnectRedis(boolean force) {
        if (redisClient == null) {
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (redisMonitor) {
            if (redisCommands != null && redisConnection != null && redisConnection.isOpen()) {
                return;
            }
            if (!force && now < nextRedisReconnectAt) {
                return;
            }

            closeRedisConnection();

            try {
                redisConnection = redisClient.connect();
                redisCommands = redisConnection.sync();
                redisReconnectFailures = 0;
                nextRedisReconnectAt = 0L;
            } catch (Exception ignored) {
                redisCommands = null;
                redisConnection = null;
                redisReconnectFailures = Math.min(redisReconnectFailures + 1, 10);
                long backoffMultiplier = 1L << Math.max(0, redisReconnectFailures - 1);
                long backoff = Math.min(
                        REDIS_RECONNECT_MIN_BACKOFF_MS * backoffMultiplier,
                        REDIS_RECONNECT_MAX_BACKOFF_MS
                );
                nextRedisReconnectAt = now + backoff;
            }
        }
    }

    private void markRedisUnavailable() {
        synchronized (redisMonitor) {
            closeRedisConnection();
            redisReconnectFailures = Math.min(redisReconnectFailures + 1, 10);
            long backoffMultiplier = 1L << Math.max(0, redisReconnectFailures - 1);
            long backoff = Math.min(
                    REDIS_RECONNECT_MIN_BACKOFF_MS * backoffMultiplier,
                    REDIS_RECONNECT_MAX_BACKOFF_MS
            );
            nextRedisReconnectAt = System.currentTimeMillis() + backoff;
        }
    }

    private void closeRedisConnection() {
        if (redisConnection != null) {
            redisConnection.close();
        }
        redisConnection = null;
        redisCommands = null;
    }
}
