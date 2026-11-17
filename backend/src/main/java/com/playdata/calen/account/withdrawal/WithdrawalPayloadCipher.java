package com.playdata.calen.account.withdrawal;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Encrypts durable cleanup targets; an ephemeral fallback would make retries impossible after restart. */
@Component
public class WithdrawalPayloadCipher {
    private static final int NONCE_BYTES = 12;
    private static final int MAX_PLAINTEXT_BYTES = 2048;
    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;
    private final SecretKey fingerprintKey;

    public WithdrawalPayloadCipher(@Value("${app.security.withdrawal-task-seal-key:}") String configuredKey) {
        String secret = configuredKey == null ? "" : configuredKey.trim();
        if (secret.length() < 32 || secret.toLowerCase(Locale.ROOT).startsWith("change-me")) {
            key = null;
            fingerprintKey = null;
            return;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("calen-withdrawal-task-v1\0".getBytes(StandardCharsets.UTF_8));
            key = new SecretKeySpec(digest.digest(secret.getBytes(StandardCharsets.UTF_8)), "AES");
            digest.update("calen-withdrawal-fingerprint-v1\0".getBytes(StandardCharsets.UTF_8));
            fingerprintKey = new SecretKeySpec(digest.digest(secret.getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Unable to initialize withdrawal task encryption.", failure);
        }
    }

    public boolean isConfigured() { return key != null; }

    /** Keyed fingerprints support idempotency without storing the target or a guessable plain hash. */
    public String fingerprint(UUID requestId, String namespace, String value) {
        requireKey();
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        if (requestId == null || namespace == null || !namespace.matches("[A-Z_]{1,50}")
                || bytes.length == 0 || bytes.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("Invalid withdrawal task fingerprint input.");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(fingerprintKey);
            mac.update((requestId + ":" + namespace + ":").getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(bytes));
        } catch (GeneralSecurityException failure) {
            throw new ServiceUnavailableException("탈퇴 작업 정보를 안전하게 확인할 수 없습니다.");
        }
    }

    public String encrypt(UUID requestId, UUID taskId, String kind, String plaintext) {
        requireKey();
        byte[] content = plaintext == null ? new byte[0] : plaintext.getBytes(StandardCharsets.UTF_8);
        if (content.length == 0 || content.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("Invalid withdrawal task payload.");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context(requestId, taskId, kind));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return "v1." + encoder.encodeToString(nonce) + "." + encoder.encodeToString(cipher.doFinal(content));
        } catch (GeneralSecurityException failure) {
            throw new ServiceUnavailableException("탈퇴 작업 정보를 안전하게 저장할 수 없습니다.");
        }
    }

    public String decrypt(UUID requestId, UUID taskId, String kind, String encoded) {
        requireKey();
        try {
            if (encoded == null || encoded.length() > 4096) throw new IllegalArgumentException();
            String[] parts = encoded.split("\\.", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] nonce = decoder.decode(parts[1]);
            if (nonce.length != NONCE_BYTES) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context(requestId, taskId, kind));
            byte[] plaintext = cipher.doFinal(decoder.decode(parts[2]));
            if (plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT_BYTES) throw new IllegalArgumentException();
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            // No target, ciphertext, or cryptographic error is included in the message or logs.
            throw new ServiceUnavailableException("탈퇴 작업 정보를 복구할 수 없습니다.");
        }
    }

    private void requireKey() {
        if (key == null) throw new ServiceUnavailableException("탈퇴 작업 암호화 키가 설정되지 않았습니다.");
    }

    private byte[] context(UUID requestId, UUID taskId, String kind) {
        if (requestId == null || taskId == null || kind == null || !kind.matches("[A-Z_]{1,40}")) {
            throw new IllegalArgumentException("Invalid withdrawal task context.");
        }
        return (requestId + ":" + taskId + ":" + kind).getBytes(StandardCharsets.UTF_8);
    }
}
