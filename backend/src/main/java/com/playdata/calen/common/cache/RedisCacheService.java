package com.playdata.calen.common.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AtomicInteger redisConnectionAvailable = new AtomicInteger(0);
    private final AtomicBoolean reconnecting = new AtomicBoolean();
    private final ExecutorService reconnectExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "redis-cache-reconnect");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    private boolean redisAvailabilityGaugeRegistered = false;

    @Value("${app.redis.cache.host:}")
    private String redisCacheHost;

    @Value("${app.redis.cache.port:6379}")
    private int redisCachePort;

    @Value("${app.redis.cache.password:}")
    private String redisCachePassword;

    @Value("${app.redis.cache.username:}")
    private String redisCacheUsername;

    @Value("${app.redis.cache.key-prefix:}")
    private String redisCacheKeyPrefix;

    @Value("${app.redis.cache.database:0}")
    private int redisCacheDatabase;

    @Value("${app.redis.cache.ssl:false}")
    private boolean redisCacheSsl;

    private volatile RedisClient redisClient;
    private volatile StatefulRedisConnection<String, String> redisConnection;
    private volatile RedisCommands<String, String> redisCommands;
    private volatile long nextRedisReconnectAt = 0L;
    private volatile boolean stopped;
    private int redisReconnectFailures = 0;

    @PostConstruct
    void initialize() {
        if (!StringUtils.hasText(redisCacheHost)) {
            return;
        }

        registerRedisAvailabilityGauge();

        redisClient = RedisClient.create(RedisConnectionSupport.buildUri(
                redisCacheHost, redisCachePort, redisCacheDatabase,
                redisCacheUsername, redisCachePassword, redisCacheSsl
        ));
        requestReconnect();
    }

    @PreDestroy
    void shutdown() {
        stopped = true;
        reconnectExecutor.shutdown();
        try {
            if (!reconnectExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                reconnectExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            reconnectExecutor.shutdownNow();
        }
        closeQuietly(detachConnection());
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
            String cachedValue = commands.get(RedisConnectionSupport.key(redisCacheKeyPrefix, key));
            if (!StringUtils.hasText(cachedValue)) {
                return null;
            }
            return objectMapper.readValue(cachedValue, valueType);
        } catch (JsonProcessingException ignored) {
            return null;
        } catch (Exception ignored) {
            markRedisUnavailable(commands);
            return null;
        }
    }

    public <T> T get(String key, TypeReference<T> valueType) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return null;
        }

        try {
            String cachedValue = commands.get(RedisConnectionSupport.key(redisCacheKeyPrefix, key));
            if (!StringUtils.hasText(cachedValue)) {
                return null;
            }
            return objectMapper.readValue(cachedValue, valueType);
        } catch (JsonProcessingException ignored) {
            return null;
        } catch (Exception ignored) {
            markRedisUnavailable(commands);
            return null;
        }
    }

    public void set(String key, Object value, Duration ttl) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }

        try {
            commands.setex(RedisConnectionSupport.key(redisCacheKeyPrefix, key), Math.max(1L, ttl.getSeconds()), objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException ignored) {
            // An incompatible cache value must not disable a healthy Redis connection.
        } catch (Exception ignored) {
            markRedisUnavailable(commands);
        }
    }

    public long delete(String... keys) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null || keys == null || keys.length == 0) {
            return -1L;
        }

        try {
            return commands.del(RedisConnectionSupport.keys(redisCacheKeyPrefix, keys));
        } catch (Exception ignored) {
            markRedisUnavailable(commands);
            return -1L;
        }
    }

    private RedisCommands<String, String> ensureRedisCommands() {
        synchronized (redisMonitor) {
            if (redisCommands != null && redisConnection != null && redisConnection.isOpen()) {
                return redisCommands;
            }
        }
        requestReconnect();
        return null;
    }

    private void requestReconnect() {
        if (stopped || redisClient == null || System.currentTimeMillis() < nextRedisReconnectAt
                || !reconnecting.compareAndSet(false, true)) {
            return;
        }
        try {
            reconnectExecutor.execute(this::connectInBackground);
        } catch (RejectedExecutionException ignored) {
            reconnecting.set(false);
        }
    }

    private void connectInBackground() {
        StatefulRedisConnection<String, String> candidate = null;
        try {
            if (stopped) return;
            closeQuietly(detachConnection());
            // Network connect/close must never hold the monitor used by HTTP requests.
            candidate = redisClient.connect();
            candidate.setTimeout(Duration.ofMillis(500));
            RedisCommands<String, String> commands = candidate.sync();
            synchronized (redisMonitor) {
                if (!stopped) {
                    redisConnection = candidate;
                    redisCommands = commands;
                    candidate = null;
                    redisConnectionAvailable.set(1);
                    redisReconnectFailures = 0;
                    nextRedisReconnectAt = 0L;
                }
            }
        } catch (Exception ignored) {
            synchronized (redisMonitor) {
                scheduleBackoff();
            }
        } finally {
            closeQuietly(candidate);
            reconnecting.set(false);
        }
    }

    private void markRedisUnavailable(RedisCommands<String, String> failedCommands) {
        StatefulRedisConnection<String, String> failedConnection;
        synchronized (redisMonitor) {
            if (redisCommands != failedCommands) return;
            failedConnection = detachConnection();
            scheduleBackoff();
        }
        try {
            reconnectExecutor.execute(() -> closeQuietly(failedConnection));
        } catch (RejectedExecutionException ignored) {
            // Shutdown owns the Redis client and closes all remaining channels.
        }
    }

    private void scheduleBackoff() {
        redisConnectionAvailable.set(0);
        redisReconnectFailures = Math.min(redisReconnectFailures + 1, 10);
        long multiplier = 1L << Math.max(0, redisReconnectFailures - 1);
        nextRedisReconnectAt = System.currentTimeMillis()
                + Math.min(REDIS_RECONNECT_MIN_BACKOFF_MS * multiplier, REDIS_RECONNECT_MAX_BACKOFF_MS);
    }

    private void registerRedisAvailabilityGauge() {
        if (meterRegistry == null || redisAvailabilityGaugeRegistered) {
            return;
        }
        Gauge.builder("calen.redis.connection.available", redisConnectionAvailable, AtomicInteger::get)
                .description("Redis connection availability by role")
                .tag("role", "cache")
                .register(meterRegistry);
        redisAvailabilityGaugeRegistered = true;
    }

    private StatefulRedisConnection<String, String> detachConnection() {
        synchronized (redisMonitor) {
            StatefulRedisConnection<String, String> previous = redisConnection;
            redisConnectionAvailable.set(0);
            redisConnection = null;
            redisCommands = null;
            return previous;
        }
    }

    private void closeQuietly(StatefulRedisConnection<String, String> connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (Exception ignored) {
            // Cache availability must not prevent application shutdown or reconnect.
        }
    }
}
