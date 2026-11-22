package com.playdata.calen.account.service;

import com.playdata.calen.common.cache.RedisConnectionSupport;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
public class RedisStateService {

    private static final long REDIS_RECONNECT_MIN_BACKOFF_MS = 30_000L;
    private static final long REDIS_RECONNECT_MAX_BACKOFF_MS = 300_000L;

    @Value("${app.redis.state.host:}")
    private String redisStateHost;

    @Value("${app.redis.state.port:6379}")
    private int redisStatePort;

    @Value("${app.redis.state.password:}")
    private String redisStatePassword;

    @Value("${app.redis.state.username:}")
    private String redisStateUsername;

    @Value("${app.redis.state.key-prefix:}")
    private String redisStateKeyPrefix;

    @Value("${app.redis.state.database:0}")
    private int redisStateDatabase;

    @Value("${app.redis.state.ssl:false}")
    private boolean redisStateSsl;

    private final Object redisMonitor = new Object();
    private final AtomicInteger redisConnectionAvailable = new AtomicInteger(0);
    private final AtomicBoolean reconnecting = new AtomicBoolean();
    private final ExecutorService reconnectExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "redis-state-reconnect");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    private boolean redisAvailabilityGaugeRegistered = false;

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> redisConnection;
    private RedisCommands<String, String> redisCommands;
    private volatile long nextRedisReconnectAt = 0L;
    private volatile boolean stopped;
    private int redisReconnectFailures = 0;

    @PostConstruct
    void initialize() {
        if (!StringUtils.hasText(redisStateHost)) {
            return;
        }

        registerRedisAvailabilityGauge();

        redisClient = RedisClient.create(RedisConnectionSupport.buildUri(
                redisStateHost, redisStatePort, redisStateDatabase,
                redisStateUsername, redisStatePassword, redisStateSsl
        ));
        // The startup connection stays synchronous so the first login after boot already uses Redis.
        connectOnce();
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

    public String get(String key) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return null;
        }

        try {
            return commands.get(RedisConnectionSupport.key(redisStateKeyPrefix, key));
        } catch (Exception ignored) {
            markRedisUnavailable(commands);
            return null;
        }
    }

    public void set(String key, String value, Duration ttl) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return;
        }

        try {
            commands.setex(RedisConnectionSupport.key(redisStateKeyPrefix, key), ttl.getSeconds(), value);
        } catch (Exception ignored) {
            markRedisUnavailable(commands);
        }
    }

    public Boolean setIfAbsent(String key, String value, Duration ttl) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return null;
        }

        try {
            return commands.set(RedisConnectionSupport.key(redisStateKeyPrefix, key), value, SetArgs.Builder.nx().ex(ttl.getSeconds())) != null;
        } catch (Exception ignored) {
            markRedisUnavailable(commands);
            return null;
        }
    }

    public Boolean compareAndDelete(String key, String expectedValue) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return null;
        }

        try {
            String currentValue = commands.get(RedisConnectionSupport.key(redisStateKeyPrefix, key));
            if (!expectedValue.equals(currentValue)) {
                return false;
            }
            return commands.del(RedisConnectionSupport.key(redisStateKeyPrefix, key)) > 0;
        } catch (Exception ignored) {
            markRedisUnavailable(commands);
            return null;
        }
    }

    public long increment(String key, Duration ttlIfNew) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return -1L;
        }

        try {
            long nextValue = commands.incr(RedisConnectionSupport.key(redisStateKeyPrefix, key));
            if (nextValue == 1L) {
                commands.expire(RedisConnectionSupport.key(redisStateKeyPrefix, key), ttlIfNew.getSeconds());
            }
            return nextValue;
        } catch (Exception ignored) {
            markRedisUnavailable(commands);
            return -1L;
        }
    }

    public long delete(String... keys) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null || keys == null || keys.length == 0) {
            return -1L;
        }

        try {
            return commands.del(RedisConnectionSupport.keys(redisStateKeyPrefix, keys));
        } catch (Exception ignored) {
            markRedisUnavailable(commands);
            return -1L;
        }
    }

    public List<String> scanKeys(String pattern) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return List.of();
        }

        try {
            List<String> keys = new ArrayList<>();
            ScanCursor cursor = ScanCursor.INITIAL;
            ScanArgs scanArgs = ScanArgs.Builder.matches(RedisConnectionSupport.pattern(redisStateKeyPrefix, pattern)).limit(200);

            do {
                KeyScanCursor<String> nextCursor = commands.scan(cursor, scanArgs);
                nextCursor.getKeys().stream()
                        .map(key -> RedisConnectionSupport.stripPrefix(redisStateKeyPrefix, key))
                        .forEach(keys::add);
                cursor = nextCursor;
            } while (!cursor.isFinished());

            return keys;
        } catch (Exception ignored) {
            markRedisUnavailable(commands);
            return List.of();
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
        try {
            if (stopped) {
                return;
            }
            closeQuietly(detachConnection());
            connectOnce();
        } finally {
            reconnecting.set(false);
        }
    }

    private void connectOnce() {
        StatefulRedisConnection<String, String> candidate = null;
        try {
            // Network connect/close must never hold the monitor used by request threads.
            candidate = redisClient.connect();
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
        }
    }

    private void markRedisUnavailable(RedisCommands<String, String> failedCommands) {
        StatefulRedisConnection<String, String> failedConnection;
        synchronized (redisMonitor) {
            if (redisCommands != failedCommands) {
                return;
            }
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
        long backoffMultiplier = 1L << Math.max(0, redisReconnectFailures - 1);
        long backoff = Math.min(
                REDIS_RECONNECT_MIN_BACKOFF_MS * backoffMultiplier,
                REDIS_RECONNECT_MAX_BACKOFF_MS
        );
        nextRedisReconnectAt = System.currentTimeMillis() + backoff;
    }

    private void registerRedisAvailabilityGauge() {
        if (meterRegistry == null || redisAvailabilityGaugeRegistered) {
            return;
        }
        Gauge.builder("calen.redis.connection.available", redisConnectionAvailable, AtomicInteger::get)
                .description("Redis connection availability by role")
                .tag("role", "state")
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
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception ignored) {
            // Redis availability must not prevent application shutdown or reconnect.
        }
    }
}
