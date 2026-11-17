package com.playdata.calen.account.service;

import com.playdata.calen.account.repository.EmailVerificationTokenRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded batches; a failed record does not block other pending registrations. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailSignupExpiryWorker {
    private static final int BATCH_SIZE = 100;
    private static final int MAX_BATCHES = 10;
    private final EmailVerificationTokenRepository tokens;
    private final EmailSignupExpiryService expiry;
    private final EmailSignupDatabaseClock signupClock;
    private long afterId;

    @Value("${app.auth.email-verification.pending-cleanup-enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public synchronized void expirePendingSignups() {
        if (!enabled) return;
        LocalDateTime now = signupClock.now();
        for (int page = 0; page < MAX_BATCHES; page++) {
            List<Long> candidates = tokens.findCleanupCandidates(now.minus(EmailSignupExpiryService.RETENTION),
                    afterId, PageRequest.of(0, BATCH_SIZE));
            if (candidates.isEmpty()) {
                afterId = 0;
                return;
            }
            for (Long userId : candidates) {
                try {
                    expiry.expireIfEligible(userId, now);
                } catch (RuntimeException exception) {
                    // Exception messages can contain account identifiers, so do not log them here.
                    log.warn("Pending signup expiry failed ({}); it will be retried on a later pass",
                            exception.getClass().getSimpleName());
                }
                afterId = userId;
            }
            if (candidates.size() < BATCH_SIZE) {
                afterId = 0;
                return;
            }
        }
    }
}
