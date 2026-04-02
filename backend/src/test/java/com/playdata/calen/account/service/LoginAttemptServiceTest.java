package com.playdata.calen.account.service;

import com.playdata.calen.common.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginAttemptServiceTest {

    @Test
    void blocksIpForTwentyFourHoursAfterFiveFailures() {
        LoginAttemptService service = new LoginAttemptService(
                Clock.fixed(Instant.parse("2026-03-28T00:00:00Z"), ZoneOffset.UTC)
        );

        String clientIp = "203.0.113.10";
        for (int index = 0; index < 5; index++) {
            service.recordFailure(clientIp);
        }

        assertThrows(TooManyRequestsException.class, () -> service.ensureAllowed(clientIp));
    }

    @Test
    void clearsIpFailuresAfterSuccessfulLogin() {
        LoginAttemptService service = new LoginAttemptService(
                Clock.fixed(Instant.parse("2026-03-28T00:00:00Z"), ZoneOffset.UTC)
        );

        String clientIp = "203.0.113.11";
        for (int index = 0; index < 4; index++) {
            service.recordFailure(clientIp);
        }

        service.recordSuccess(clientIp);

        assertDoesNotThrow(() -> service.ensureAllowed(clientIp));
    }

    @Test
    void blocksIpWhenRedisStateStoreHasActiveLock() {
        LoginAttemptService service = new LoginAttemptService(
                Clock.fixed(Instant.parse("2026-03-28T00:00:00Z"), ZoneOffset.UTC)
        );
        RedisStateService redisStateService = mock(RedisStateService.class);
        service.setRedisStateService(redisStateService);

        when(redisStateService.get("auth:login-attempt:ip:203.0.113.12:locked-until"))
                .thenReturn(Long.toString(Instant.parse("2026-03-29T00:00:00Z").toEpochMilli()));

        assertThrows(TooManyRequestsException.class, () -> service.ensureAllowed("203.0.113.12"));
    }
}
