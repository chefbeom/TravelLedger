package com.playdata.calen.account.social;

import java.io.Serializable;
import java.time.Instant;

public record KakaoPendingRegistration(
        String providerUserId,
        String email,
        String displayName,
        Instant expiresAt
) implements Serializable {
}
