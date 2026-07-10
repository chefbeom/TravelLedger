package com.playdata.calen.account.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecuritySecretSupportTest {

    @Test
    void derivesStablePurposeSeparatedKeysFromConfiguredSecret() {
        String rememberMeKey = SecuritySecretSupport.resolve("a-configured-secret", "remember-me");
        String repeatedRememberMeKey = SecuritySecretSupport.resolve("a-configured-secret", "remember-me");
        String mediaKey = SecuritySecretSupport.resolve("a-configured-secret", "travel-public-media");

        assertThat(rememberMeKey).isEqualTo(repeatedRememberMeKey);
        assertThat(mediaKey).isNotEqualTo(rememberMeKey);
    }

    @Test
    void knownInsecureDefaultsAreReplacedWithEphemeralKeys() {
        String first = SecuritySecretSupport.resolve("change-me-remember-me-key", "remember-me");
        String second = SecuritySecretSupport.resolve("change-me-remember-me-key", "remember-me");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).doesNotContain("change-me-remember-me-key");
    }
}
