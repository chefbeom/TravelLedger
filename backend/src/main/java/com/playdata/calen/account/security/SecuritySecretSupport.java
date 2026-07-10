package com.playdata.calen.account.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SecuritySecretSupport {

    private static final Logger log = LoggerFactory.getLogger(SecuritySecretSupport.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Set<String> KNOWN_INSECURE_DEFAULTS = Set.of(
            "calen-remember-me-key",
            "change-me-remember-me-key",
            "calen-session-seal-key-change-me",
            "calen-public-media-key-change-me"
    );

    private SecuritySecretSupport() {
    }

    public static String resolve(String configuredSecret, String purpose) {
        String baseSecret = configuredSecret == null ? "" : configuredSecret.trim();
        if (baseSecret.isBlank() || KNOWN_INSECURE_DEFAULTS.contains(baseSecret)) {
            byte[] generated = new byte[48];
            SECURE_RANDOM.nextBytes(generated);
            baseSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(generated);
            log.warn(
                    "{} secret is missing or uses a known insecure default. "
                            + "An ephemeral key was generated; configure JWT_KEY before production use.",
                    purpose
            );
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(purpose.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            byte[] derived = digest.digest(baseSecret.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
