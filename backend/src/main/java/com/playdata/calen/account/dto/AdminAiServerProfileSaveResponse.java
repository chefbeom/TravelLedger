package com.playdata.calen.account.dto;

/** Contains no credential value. Shared safety settings apply to all providers. */
public record AdminAiServerProfileSaveResponse(boolean credentialConfigured, boolean enforceProviderUrlAllowlist,
        String allowedProviderHosts, long connectTimeoutSeconds, long readTimeoutSeconds, boolean enabled) { }
