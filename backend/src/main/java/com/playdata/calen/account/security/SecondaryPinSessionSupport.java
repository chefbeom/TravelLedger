package com.playdata.calen.account.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecondaryPinSessionSupport {

    public static final String VERIFIED_SECONDARY_PIN_KEY = "VERIFIED_SECONDARY_PIN";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecondaryPinSessionSupport(
            @Value("${app.security.session-seal-key:${JWT_KEY:calen-session-seal-key-change-me}}") String sealKey
    ) {
        this.secretKey = new SecretKeySpec(deriveKey(sealKey), "AES");
    }

    public void storeVerifiedSecondaryPin(HttpServletRequest request, String secondaryPin) {
        HttpSession session = request.getSession(true);
        session.setAttribute(VERIFIED_SECONDARY_PIN_KEY, encrypt(secondaryPin));
    }

    public String getVerifiedSecondaryPin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(VERIFIED_SECONDARY_PIN_KEY);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return decrypt(text);
    }

    public void clearVerifiedSecondaryPin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(VERIFIED_SECONDARY_PIN_KEY);
        }
    }

    private String encrypt(String plaintext) {
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
            throw new IllegalStateException("2차 비밀번호 세션 암호화에 실패했습니다.", exception);
        }
    }

    private String decrypt(String encryptedValue) {
        try {
            String[] parts = encryptedValue.split("\\.", 2);
            if (parts.length != 2) {
                return null;
            }

            byte[] iv = Base64.getUrlDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return null;
        }
    }

    private byte[] deriveKey(String sealKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sealKey.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(digest, 32);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("세션 암호화 키를 초기화할 수 없습니다.", exception);
        }
    }
}
