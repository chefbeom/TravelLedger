package com.playdata.calen.account.dto;

import java.time.Instant;

/** Only server-verified onboarding data; provider identifiers and tokens stay in the session. */
public record KakaoRegistrationSessionResponse(String email, String displayName, Instant expiresAt) {
}
