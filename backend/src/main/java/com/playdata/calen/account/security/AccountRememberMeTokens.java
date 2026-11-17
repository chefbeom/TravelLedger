package com.playdata.calen.account.security;

import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

/** Account-owned operations use immutable IDs, not reusable login names. */
public interface AccountRememberMeTokens extends PersistentTokenRepository {
    void removeUserTokensById(Long userId);
    boolean isBoundSchemaAvailable();
    void requireBoundSchema();
}
