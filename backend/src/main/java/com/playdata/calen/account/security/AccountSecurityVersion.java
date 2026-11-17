package com.playdata.calen.account.security;

import com.playdata.calen.account.domain.AppUser;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Opaque version of security-sensitive account state; never return it to a client. */
public final class AccountSecurityVersion {
    private AccountSecurityVersion() {}

    public static String forKakaoLink(AppUser user) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, "calen-kakao-link-v1");
            add(digest, String.valueOf(user.getId()));
            add(digest, user.getLoginId());
            add(digest, user.getPasswordHash());
            add(digest, user.getSecondaryPinHash());
            add(digest, user.getEmail());
            add(digest, String.valueOf(user.getRole()));
            add(digest, Boolean.toString(user.isActive()));
            add(digest, Boolean.toString(user.isEmailVerified()));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("계정 보안 상태를 확인할 수 없습니다.", failure);
        }
    }

    public static boolean matchesKakaoLink(AppUser user, String expected) {
        if (expected == null || !expected.matches("[0-9a-f]{64}")) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                forKakaoLink(user).getBytes(StandardCharsets.US_ASCII));
    }

    private static void add(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
