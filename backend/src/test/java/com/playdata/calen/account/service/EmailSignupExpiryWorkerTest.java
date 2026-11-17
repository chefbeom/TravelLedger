package com.playdata.calen.account.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.repository.EmailVerificationTokenRepository;
import java.util.List;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EmailSignupExpiryWorkerTest {
    @Mock EmailVerificationTokenRepository tokens;
    @Mock EmailSignupExpiryService expiry;
    @Mock EmailSignupDatabaseClock clock;

    @Test void aFailedCleanupDoesNotBlockTheRemainingAccounts() {
        var worker = new EmailSignupExpiryWorker(tokens, expiry, clock);
        ReflectionTestUtils.setField(worker, "enabled", true);
        when(clock.now()).thenReturn(LocalDateTime.now());
        when(tokens.findCleanupCandidates(any(), eq(0L), any())).thenReturn(List.of(3L, 7L));
        doThrow(new IllegalStateException("sensitive@example.test")).when(expiry).expireIfEligible(eq(3L), any());
        worker.expirePendingSignups();
        verify(expiry).expireIfEligible(eq(7L), any());
    }
}
