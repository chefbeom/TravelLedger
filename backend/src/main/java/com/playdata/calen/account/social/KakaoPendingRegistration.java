package com.playdata.calen.account.social;

import java.io.Serializable;

public record KakaoPendingRegistration(
        String providerUserId,
        String email,
        String displayName
) implements Serializable {
}
