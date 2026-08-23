package com.playdata.calen.account.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AdminOpsSecretCipher {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private final SecretKey secretKey;
    private final SecretKey legacySecretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public AdminOpsSecretCipher(
            @Value("${OPS_CONTROL_SEAL_KEY:}") String sealKey,
            @Value("${JWT_KEY:}") String legacyJwtKey
    ) {
        this(sealKey, legacyJwtKey, true);
    }

    public AdminOpsSecretCipher(String sealKey) {
        this(sealKey, "", false);
    }

    AdminOpsSecretCipher(String sealKey, String legacyJwtKey, boolean allowLegacyKey) {
        String configuredSealKey = normalize(sealKey);
        String configuredLegacyJwtKey = normalize(legacyJwtKey);
        String primaryKeySource = configuredSealKey.isBlank() ? configuredLegacyJwtKey : configuredSealKey;
        this.secretKey = new SecretKeySpec(
                deriveKey(SecuritySecretSupport.resolve(primaryKeySource, "admin-ops-control")),
                "AES"
        );
        this.legacySecretKey = allowLegacyKey
                && !configuredSealKey.isBlank()
                && !configuredLegacyJwtKey.isBlank()
                && !configuredSealKey.equals(configuredLegacyJwtKey)
                ? new SecretKeySpec(
                        deriveKey(SecuritySecretSupport.resolve(configuredLegacyJwtKey, "admin-ops-control")),
                        "AES"
                )
                : null;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return "";
        }

        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                    + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt administrator AI credentials.", exception);
        }
    }

    public Optional<String> decrypt(String encryptedValue) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return Optional.empty();
        }

        try {
            String[] parts = encryptedValue.split("\\.", 2);
            if (parts.length != 2) {
                return Optional.empty();
            }

            byte[] iv = Base64.getUrlDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[1]);

            Optional<String> decrypted = decryptWithKey(iv, encrypted, secretKey);
            if (decrypted.isPresent()) {
                return decrypted;
            }
            return legacySecretKey == null ? Optional.empty() : decryptWithKey(iv, encrypted, legacySecretKey);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Optional<String> decryptWithKey(byte[] iv, byte[] encrypted, SecretKey key) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            String plaintext = new String(decrypted, StandardCharsets.UTF_8);
            return plaintext.isBlank() ? Optional.empty() : Optional.of(plaintext);
        } catch (GeneralSecurityException exception) {
            return Optional.empty();
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private byte[] deriveKey(String sealKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sealKey.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(digest, 32);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to initialize administrator credential encryption.", exception);
        }
    }
}
