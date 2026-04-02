package com.playdata.calen.account.service;

import com.playdata.calen.common.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LoginAttemptService {

    private static final int MAX_IP_FAILURES = 5;
    private static final Duration FAILURE_WINDOW = Duration.ofHours(24);
    private static final Duration LOCK_DURATION = Duration.ofHours(24);
    private static final String REDIS_IP_PREFIX = "auth:login-attempt:ip:";
    private static final String REDIS_COUNT_SUFFIX = ":count";
    private static final String REDIS_LOCK_SUFFIX = ":locked-until";

    private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();
    private final Clock clock;
    private RedisStateService redisStateService;

    public LoginAttemptService() {
        this(Clock.systemUTC());
    }

    LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    @Autowired(required = false)
    void setRedisStateService(RedisStateService redisStateService) {
        this.redisStateService = redisStateService;
    }

    public void ensureAllowed(String clientIp) {
        Instant now = Instant.now(clock);
        enforceLock(keyForIp(clientIp), now);
        enforceRedisLock(normalize(clientIp), now);
    }

    public void recordSuccess(String clientIp) {
        attempts.remove(keyForIp(clientIp));
        clearRedisFailures(normalize(clientIp));
    }

    public void recordFailure(String clientIp) {
        Instant now = Instant.now(clock);
        updateFailure(keyForIp(clientIp), now);
        pruneExpiredEntries(now);
        updateRedisFailure(normalize(clientIp), now);
    }

    public void clearFailures(String clientIp) {
        attempts.remove(keyForIp(clientIp));
        clearRedisFailures(normalize(clientIp));
    }

    public List<BlockedIpSnapshot> getBlockedIps() {
        Instant now = Instant.now(clock);
        pruneExpiredEntries(now);
        Map<String, BlockedIpSnapshot> merged = new LinkedHashMap<>();

        attempts.entrySet().stream()
                .filter(entry -> entry.getValue().lockedUntil() != null && entry.getValue().lockedUntil().isAfter(now))
                .map(entry -> new BlockedIpSnapshot(
                        entry.getKey().replaceFirst("^IP:", ""),
                        entry.getValue().failureCount(),
                        entry.getValue().lockedUntil()
                ))
                .forEach(snapshot -> merged.put(snapshot.clientIp(), snapshot));

        for (BlockedIpSnapshot snapshot : loadRedisBlockedIps(now)) {
            merged.merge(snapshot.clientIp(), snapshot, this::mergeSnapshots);
        }

        return merged.values().stream()
                .sorted(Comparator.comparing(BlockedIpSnapshot::lockedUntil).reversed())
                .toList();
    }

    private void enforceLock(String key, Instant now) {
        AttemptState state = attempts.get(key);
        if (state == null) {
            return;
        }

        if (state.lockedUntil() != null && state.lockedUntil().isAfter(now)) {
            throw new TooManyRequestsException("같은 IP에서 로그인 실패가 5회 이상 발생해 24시간 동안 차단되었습니다.");
        }

        if (state.windowStartedAt().plus(FAILURE_WINDOW).isBefore(now) && state.lockedUntil() == null) {
            attempts.remove(key, state);
        }
    }

    private void updateFailure(String key, Instant now) {
        attempts.compute(key, (ignored, existing) -> {
            AttemptState current = existing;
            if (current == null || current.windowStartedAt().plus(FAILURE_WINDOW).isBefore(now)) {
                current = new AttemptState(0, now, null);
            }

            int nextCount = current.failureCount() + 1;
            Instant lockedUntil = nextCount >= MAX_IP_FAILURES ? now.plus(LOCK_DURATION) : current.lockedUntil();
            return new AttemptState(nextCount, current.windowStartedAt(), lockedUntil);
        });
    }

    private void enforceRedisLock(String clientIp, Instant now) {
        if (!StringUtils.hasText(clientIp) || redisStateService == null) {
            return;
        }

        String lockedUntilValue = redisStateService.get(redisLockKey(clientIp));
        if (!StringUtils.hasText(lockedUntilValue)) {
            return;
        }

        try {
            Instant lockedUntil = Instant.ofEpochMilli(Long.parseLong(lockedUntilValue));
            if (lockedUntil.isAfter(now)) {
                throw new TooManyRequestsException("같은 IP에서 로그인 실패가 5회 이상 발생해 24시간 동안 차단되었습니다.");
            }
        } catch (NumberFormatException ignored) {
            clearRedisFailures(clientIp);
        }
    }

    private void updateRedisFailure(String clientIp, Instant now) {
        if (!StringUtils.hasText(clientIp) || redisStateService == null) {
            return;
        }

        long nextCount = redisStateService.increment(redisCountKey(clientIp), FAILURE_WINDOW);
        if (nextCount >= MAX_IP_FAILURES) {
            redisStateService.set(
                    redisLockKey(clientIp),
                    Long.toString(now.plus(LOCK_DURATION).toEpochMilli()),
                    LOCK_DURATION
            );
        }
    }

    private void clearRedisFailures(String clientIp) {
        if (!StringUtils.hasText(clientIp) || redisStateService == null) {
            return;
        }
        redisStateService.delete(redisCountKey(clientIp), redisLockKey(clientIp));
    }

    private List<BlockedIpSnapshot> loadRedisBlockedIps(Instant now) {
        if (redisStateService == null) {
            return List.of();
        }

        List<BlockedIpSnapshot> blockedIps = new ArrayList<>();
        for (String lockKey : redisStateService.scanKeys(REDIS_IP_PREFIX + "*" + REDIS_LOCK_SUFFIX)) {
            String clientIp = extractClientIp(lockKey);
            if (!StringUtils.hasText(clientIp)) {
                continue;
            }

            String lockedUntilValue = redisStateService.get(lockKey);
            if (!StringUtils.hasText(lockedUntilValue)) {
                continue;
            }

            try {
                Instant lockedUntil = Instant.ofEpochMilli(Long.parseLong(lockedUntilValue));
                if (!lockedUntil.isAfter(now)) {
                    continue;
                }

                int failureCount = parseFailureCount(redisStateService.get(redisCountKey(clientIp)));
                blockedIps.add(new BlockedIpSnapshot(clientIp, failureCount, lockedUntil));
            } catch (NumberFormatException ignored) {
                clearRedisFailures(clientIp);
            }
        }
        return blockedIps;
    }

    private BlockedIpSnapshot mergeSnapshots(BlockedIpSnapshot left, BlockedIpSnapshot right) {
        return new BlockedIpSnapshot(
                left.clientIp(),
                Math.max(left.failureCount(), right.failureCount()),
                left.lockedUntil().isAfter(right.lockedUntil()) ? left.lockedUntil() : right.lockedUntil()
        );
    }

    private int parseFailureCount(String value) {
        if (!StringUtils.hasText(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
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

    private String keyForIp(String clientIp) {
        return "IP:" + normalize(clientIp);
    }

    private String redisCountKey(String clientIp) {
        return REDIS_IP_PREFIX + clientIp + REDIS_COUNT_SUFFIX;
    }

    private String redisLockKey(String clientIp) {
        return REDIS_IP_PREFIX + clientIp + REDIS_LOCK_SUFFIX;
    }

    private String extractClientIp(String lockKey) {
        if (!StringUtils.hasText(lockKey)
                || !lockKey.startsWith(REDIS_IP_PREFIX)
                || !lockKey.endsWith(REDIS_LOCK_SUFFIX)) {
            return "";
        }
        return lockKey.substring(
                REDIS_IP_PREFIX.length(),
                lockKey.length() - REDIS_LOCK_SUFFIX.length()
        );
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase() : "unknown";
    }

    private record AttemptState(
            int failureCount,
            Instant windowStartedAt,
            Instant lockedUntil
    ) {
    }

    public record BlockedIpSnapshot(
            String clientIp,
            int failureCount,
            Instant lockedUntil
    ) {
    }
}
