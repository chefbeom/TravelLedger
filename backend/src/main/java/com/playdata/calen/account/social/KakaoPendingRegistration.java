package com.playdata.calen.account.social;

import java.io.Serializable;
import java.time.Instant;

public record KakaoPendingRegistration(
        String providerUserId,
        String email,
        String displayName,
        Instant expiresAt,
        Long providerAppId
) implements Serializable {
    public KakaoPendingRegistration(String providerUserId, String email, String displayName, Instant expiresAt) {
        this(providerUserId, email, displayName, expiresAt, null);
    }
}
