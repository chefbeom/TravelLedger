package com.playdata.calen.account.social;

public record KakaoIdentity(
        String providerUserId,
        String email,
        String displayName,
        boolean emailVerified
) {
}
