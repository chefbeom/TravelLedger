package com.playdata.calen.account.service;

import com.playdata.calen.common.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SecondaryPinAttemptService {

    private static final int MAX_FAILURES = 5;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final String REDIS_PREFIX = "auth:secondary-pin:user:";
    private static final String COUNT_SUFFIX = ":count";
    private static final String LOCK_SUFFIX = ":locked-until";
    private static final String LOCK_MESSAGE =
            "Too many failed secondary PIN attempts. Try again in 15 minutes.";

    private final Map<Long, AttemptState> attempts = new ConcurrentHashMap<>();
    private final Clock clock;
    private RedisStateService redisStateService;

    public SecondaryPinAttemptService() {
        this(Clock.systemUTC());
    }

    SecondaryPinAttemptService(Clock clock) {
        this.clock = clock;
    }

    @Autowired(required = false)
    void setRedisStateService(RedisStateService redisStateService) {
        this.redisStateService = redisStateService;
    }

    public void ensureAllowed(Long userId) {
        if (userId == null) {
            throw new TooManyRequestsException(LOCK_MESSAGE);
        }
        Instant now = Instant.now(clock);
        enforceLocalLock(userId, now);
        enforceRedisLock(userId, now);
    }

    public void recordFailure(Long userId) {
        if (userId == null) {
            throw new TooManyRequestsException(LOCK_MESSAGE);
        }
        Instant now = Instant.now(clock);
        AttemptState state = attempts.compute(userId, (ignored, existing) -> nextState(existing, now));
        long distributedCount = updateRedisFailure(userId, now);
        if (state.failureCount() >= MAX_FAILURES || distributedCount >= MAX_FAILURES) {
            throw new TooManyRequestsException(LOCK_MESSAGE);
        }
        pruneExpiredEntries(now);
    }

    public void recordSuccess(Long userId) {
        if (userId == null) {
            return;
        }
        attempts.remove(userId);
        clearRedisFailures(userId);
    }

    private AttemptState nextState(AttemptState existing, Instant now) {
        AttemptState current = existing;
        if (current == null || current.windowStartedAt().plus(FAILURE_WINDOW).isBefore(now)) {
            current = new AttemptState(0, now, null);
        }
        int nextCount = current.failureCount() + 1;
        Instant lockedUntil = nextCount >= MAX_FAILURES ? now.plus(LOCK_DURATION) : current.lockedUntil();
        return new AttemptState(nextCount, current.windowStartedAt(), lockedUntil);
    }

    private void enforceLocalLock(Long userId, Instant now) {
        AttemptState state = attempts.get(userId);
        if (state == null) {
            return;
        }
        if (state.lockedUntil() != null && state.lockedUntil().isAfter(now)) {
            throw new TooManyRequestsException(LOCK_MESSAGE);
        }
        if (state.windowStartedAt().plus(FAILURE_WINDOW).isBefore(now)) {
            attempts.remove(userId, state);
        }
    }

    private void enforceRedisLock(Long userId, Instant now) {
        if (redisStateService == null) {
            return;
        }
        String value = redisStateService.get(redisLockKey(userId));
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            if (Instant.ofEpochMilli(Long.parseLong(value)).isAfter(now)) {
                throw new TooManyRequestsException(LOCK_MESSAGE);
            }
        } catch (NumberFormatException ignored) {
            clearRedisFailures(userId);
        }
    }

    private long updateRedisFailure(Long userId, Instant now) {
        if (redisStateService == null) {
            return 0L;
        }
        long nextCount = redisStateService.increment(redisCountKey(userId), FAILURE_WINDOW);
        if (nextCount >= MAX_FAILURES) {
            redisStateService.set(
                    redisLockKey(userId),
                    Long.toString(now.plus(LOCK_DURATION).toEpochMilli()),
                    LOCK_DURATION
            );
        }
        return nextCount;
    }

    private void clearRedisFailures(Long userId) {
        if (redisStateService != null) {
            redisStateService.delete(redisCountKey(userId), redisLockKey(userId));
        }
    }

    private void pruneExpiredEntries(Instant now) {
        attempts.entrySet().removeIf(entry -> {
            AttemptState state = entry.getValue();
            boolean lockExpired = state.lockedUntil() == null || !state.lockedUntil().isAfter(now);
            boolean windowExpired = state.windowStartedAt().plus(FAILURE_WINDOW).isBefore(now);
            return lockExpired && windowExpired;
        });
    }

    private String redisCountKey(Long userId) {
        return REDIS_PREFIX + userId + COUNT_SUFFIX;
    }

    private String redisLockKey(Long userId) {
        return REDIS_PREFIX + userId + LOCK_SUFFIX;
    }

    private record AttemptState(int failureCount, Instant windowStartedAt, Instant lockedUntil) {
    }
}
