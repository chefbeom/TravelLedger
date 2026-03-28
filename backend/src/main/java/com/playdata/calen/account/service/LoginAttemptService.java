package com.playdata.calen.account.service;

import com.playdata.calen.common.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LoginAttemptService {

    private static final int MAX_IP_FAILURES = 5;
    private static final Duration FAILURE_WINDOW = Duration.ofHours(24);
    private static final Duration LOCK_DURATION = Duration.ofHours(24);

    private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptService() {
        this(Clock.systemUTC());
    }

    LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    public void ensureAllowed(String clientIp) {
        Instant now = Instant.now(clock);
        enforceLock(keyForIp(clientIp), now);
    }

    public void recordSuccess(String clientIp) {
        attempts.remove(keyForIp(clientIp));
    }

    public void recordFailure(String clientIp) {
        Instant now = Instant.now(clock);
        updateFailure(keyForIp(clientIp), now);
        pruneExpiredEntries(now);
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
