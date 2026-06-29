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
}