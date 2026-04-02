package com.playdata.calen.account.service;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
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

    @Value("${app.redis.state.database:0}")
    private int redisStateDatabase;

    @Value("${app.redis.state.ssl:false}")
    private boolean redisStateSsl;

    private final Object redisMonitor = new Object();

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> redisConnection;
    private RedisCommands<String, String> redisCommands;
    private long nextRedisReconnectAt = 0L;
    private int redisReconnectFailures = 0;

    @PostConstruct
    void initialize() {
        if (!StringUtils.hasText(redisStateHost)) {
            return;
        }

        RedisURI.Builder redisUriBuilder = RedisURI.builder()
                .withHost(redisStateHost.trim())
                .withPort(redisStatePort)
                .withDatabase(redisStateDatabase)
                .withTimeout(Duration.ofSeconds(3));

        if (StringUtils.hasText(redisStatePassword)) {
            redisUriBuilder.withPassword(redisStatePassword.toCharArray());
        }
        if (redisStateSsl) {
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

    public String get(String key) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return null;
        }

        try {
            return commands.get(key);
        } catch (Exception ignored) {
            markRedisUnavailable();
            return null;
        }
    }

    public void set(String key, String value, Duration ttl) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return;
        }

        try {
            commands.setex(key, ttl.getSeconds(), value);
        } catch (Exception ignored) {
            markRedisUnavailable();
        }
    }

    public Boolean setIfAbsent(String key, String value, Duration ttl) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return null;
        }

        try {
            return commands.set(key, value, SetArgs.Builder.nx().ex(ttl.getSeconds())) != null;
        } catch (Exception ignored) {
            markRedisUnavailable();
            return null;
        }
    }

    public Boolean compareAndDelete(String key, String expectedValue) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return null;
        }

        try {
            String currentValue = commands.get(key);
            if (!expectedValue.equals(currentValue)) {
                return false;
            }
            return commands.del(key) > 0;
        } catch (Exception ignored) {
            markRedisUnavailable();
            return null;
        }
    }

    public long increment(String key, Duration ttlIfNew) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return -1L;
        }

        try {
            long nextValue = commands.incr(key);
            if (nextValue == 1L) {
                commands.expire(key, ttlIfNew.getSeconds());
            }
            return nextValue;
        } catch (Exception ignored) {
            markRedisUnavailable();
            return -1L;
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

    public List<String> scanKeys(String pattern) {
        RedisCommands<String, String> commands = ensureRedisCommands();
        if (commands == null) {
            return List.of();
        }

        try {
            List<String> keys = new ArrayList<>();
            ScanCursor cursor = ScanCursor.INITIAL;
            ScanArgs scanArgs = ScanArgs.Builder.matches(pattern).limit(200);

            do {
                KeyScanCursor<String> nextCursor = commands.scan(cursor, scanArgs);
                keys.addAll(nextCursor.getKeys());
                cursor = nextCursor;
            } while (!cursor.isFinished());

            return keys;
        } catch (Exception ignored) {
            markRedisUnavailable();
            return List.of();
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
