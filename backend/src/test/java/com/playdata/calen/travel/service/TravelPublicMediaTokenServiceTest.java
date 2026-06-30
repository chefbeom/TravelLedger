package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TravelPublicMediaTokenServiceTest {

    private final TravelPublicMediaTokenService tokenService = new TravelPublicMediaTokenService("test-public-media-secret");

    @Test
    void issuedTokenMatchesOnlyOriginalMediaId() {
        String token = tokenService.issueToken(100L);

        assertThat(tokenService.matches(100L, token)).isTrue();
        assertThat(tokenService.matches(100L, "  " + token + "  ")).isTrue();
        assertThat(tokenService.matches(101L, token)).isFalse();
    }

    @Test
    void invalidOrMissingTokensDoNotMatch() {
        String token = tokenService.issueToken(100L);

        assertThat(tokenService.matches(null, token)).isFalse();
        assertThat(tokenService.matches(100L, null)).isFalse();
        assertThat(tokenService.matches(100L, "")).isFalse();
        assertThat(tokenService.matches(100L, "   ")).isFalse();
        assertThat(tokenService.matches(100L, token + "tampered")).isFalse();
    }

    @Test
    void tokenIssuedWithDifferentSecretDoesNotMatch() {
        String token = tokenService.issueToken(100L);
        TravelPublicMediaTokenService otherSecretService = new TravelPublicMediaTokenService("different-public-media-secret");

        assertThat(otherSecretService.matches(100L, token)).isFalse();
    }

    @Test
    void rotatingPublicMediaKeyRevokesPreviouslyIssuedToken() {
        TravelPublicMediaTokenService originalKeyService = new TravelPublicMediaTokenService("travel-public-media-key-v1");
        TravelPublicMediaTokenService rotatedKeyService = new TravelPublicMediaTokenService("travel-public-media-key-v2");
        String oldToken = originalKeyService.issueToken(100L);
        String newToken = rotatedKeyService.issueToken(100L);

        assertThat(originalKeyService.matches(100L, oldToken)).isTrue();
        assertThat(rotatedKeyService.matches(100L, oldToken)).isFalse();
        assertThat(rotatedKeyService.matches(100L, newToken)).isTrue();
    }
}