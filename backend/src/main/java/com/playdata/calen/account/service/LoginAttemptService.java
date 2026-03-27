package com.playdata.calen.account.service;

import com.playdata.calen.common.exception.TooManyRequestsException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LoginAttemptService {

    private static final int MAX_LOGIN_FAILURES = 5;
    private static final int MAX_IP_FAILURES = 20;
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();

    public void ensureAllowed(String loginId, String clientIp) {
        Instant now = Instant.now();
        enforceLock(keyForLogin(loginId), now);
        enforceLock(keyForIp(clientIp), now);
    }

    public void recordSuccess(String loginId, String clientIp) {
        attempts.remove(keyForLogin(loginId));
        attempts.remove(keyForIp(clientIp));
    }

    public void recordFailure(String loginId, String clientIp) {
        Instant now = Instant.now();
        updateFailure(keyForLogin(loginId), MAX_LOGIN_FAILURES, now);
        updateFailure(keyForIp(clientIp), MAX_IP_FAILURES, now);
        pruneExpiredEntries(now);
    }

    private void enforceLock(String key, Instant now) {
        AttemptState state = attempts.get(key);
        if (state == null) {
            return;
        }

        if (state.lockedUntil() != null && state.lockedUntil().isAfter(now)) {
            throw new TooManyRequestsException("로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }

        if (state.windowStartedAt().plus(FAILURE_WINDOW).isBefore(now) && state.lockedUntil() == null) {
            attempts.remove(key, state);
        }
    }

    private void updateFailure(String key, int maxFailures, Instant now) {
        attempts.compute(key, (ignored, existing) -> {
            AttemptState current = existing;
            if (current == null || current.windowStartedAt().plus(FAILURE_WINDOW).isBefore(now)) {
                current = new AttemptState(0, now, null);
            }

            int nextCount = current.failureCount() + 1;
            Instant lockedUntil = nextCount >= maxFailures ? now.plus(LOCK_DURATION) : current.lockedUntil();
            return new AttemptState(nextCount, current.windowStartedAt(), lockedUntil);
        });
    }

    private void pruneExpiredEntries(Instant now) {
        attempts.entrySet().removeIf(entry -> {
            AttemptState state = entry.getValue();
            boolean lockExpired = state.lockedUntil() == null || !state.lockedUntil().isAfter(now);
            boolean windowExpired = state.windowStartedAt().plus(FAILURE_WINDOW).isBefore(now);
            return lockExpired && windowExpired;
        });
    }

    private String keyForLogin(String loginId) {
        return "LOGIN:" + normalize(loginId);
    }

    private String keyForIp(String clientIp) {
        return "IP:" + normalize(clientIp);
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
}
