package com.playdata.calen.account.dto;

import java.util.List;

/**
 * Public account-registration capabilities. Social providers remain empty until
 * their OAuth credentials and callbacks are configured.
 */
public record PublicRegistrationOptionsResponse(
        boolean publicRegistrationEnabled,
        List<String> socialLoginProviders
) {
}
