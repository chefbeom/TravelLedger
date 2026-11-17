package com.playdata.calen.account.security;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.util.StringUtils;

/** Prevents authentication codes and secrets from being sent over remote cleartext HTTP. */
public final class AuthEndpointPolicy {
    private AuthEndpointPolicy() {}

    public static boolean isSafeUrl(String value, boolean allowQuery) {
        if (!StringUtils.hasText(value)) return false;
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme(), host = uri.getHost();
            boolean localHttp = "http".equalsIgnoreCase(scheme)
                    && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                        || "[::1]".equals(host) || "::1".equals(host));
            return ("https".equalsIgnoreCase(scheme) || localHttp) && StringUtils.hasText(host)
                    && uri.getUserInfo() == null && uri.getFragment() == null && uri.getPort() <= 65535
                    && (allowQuery || uri.getQuery() == null);
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
