package com.playdata.calen.account.social;

public record KakaoIdentity(
        String providerUserId,
        String email,
        String displayName,
        boolean emailVerified,
        Long providerAppId
) {
    public KakaoIdentity(String providerUserId, String email, String displayName, boolean emailVerified) {
        this(providerUserId, email, displayName, emailVerified, null);
    }
}
