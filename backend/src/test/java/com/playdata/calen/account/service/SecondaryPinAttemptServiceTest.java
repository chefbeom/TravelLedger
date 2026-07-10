package com.playdata.calen.account.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playdata.calen.common.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SecondaryPinAttemptServiceTest {

    @Test
    void locksTheUserAfterFiveFailedPinAttempts() {
        SecondaryPinAttemptService service = new SecondaryPinAttemptService(
                Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC)
        );

        for (int attempt = 1; attempt < 5; attempt++) {
            assertThatCode(() -> service.recordFailure(1L)).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> service.recordFailure(1L))
                .isInstanceOf(TooManyRequestsException.class);
        assertThatThrownBy(() -> service.ensureAllowed(1L))
                .isInstanceOf(TooManyRequestsException.class);

        assertThatCode(() -> service.ensureAllowed(2L)).doesNotThrowAnyException();
    }

    @Test
    void successfulVerificationClearsPreviousFailures() {
        SecondaryPinAttemptService service = new SecondaryPinAttemptService(
                Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC)
        );

        for (int attempt = 0; attempt < 4; attempt++) {
            service.recordFailure(1L);
        }
        service.recordSuccess(1L);

        assertThatCode(() -> service.ensureAllowed(1L)).doesNotThrowAnyException();
        assertThatCode(() -> service.recordFailure(1L)).doesNotThrowAnyException();
    }
}