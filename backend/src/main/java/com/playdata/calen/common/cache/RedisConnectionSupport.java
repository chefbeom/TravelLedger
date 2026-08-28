package com.playdata.calen.common.cache;

import io.lettuce.core.RedisURI;
import java.time.Duration;
import org.springframework.util.StringUtils;

/** Shared Lettuce URI and tenant-key handling for optional Redis stores. */
public final class RedisConnectionSupport {

    private RedisConnectionSupport() {
    }

    public static RedisURI buildUri(
            String host,
            int port,
            int database,
            String username,
            String password,
            boolean ssl
    ) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(host.trim())
                .withPort(port)
                .withDatabase(database)
                .withTimeout(Duration.ofSeconds(3));

        if (StringUtils.hasText(username)) {
            builder.withAuthentication(username.trim(), password == null ? new char[0] : password.toCharArray());
        } else if (StringUtils.hasText(password)) {
            builder.withPassword(password.toCharArray());
        }
        if (ssl) {
            builder.withSsl(true);
        }
        return builder.build();
    }

    public static String key(String prefix, String key) {
        return normalizePrefix(prefix) + key;
    }

    public static String[] keys(String prefix, String... keys) {
        if (keys == null) {
            return new String[0];
        }
        String[] result = new String[keys.length];
        for (int index = 0; index < keys.length; index++) {
            result[index] = key(prefix, keys[index]);
        }
        return result;
    }

    public static String pattern(String prefix, String pattern) {
        return normalizePrefix(prefix) + pattern;
    }

    public static String stripPrefix(String prefix, String key) {
        String normalizedPrefix = normalizePrefix(prefix);
        if (!StringUtils.hasText(key) || normalizedPrefix.isEmpty() || !key.startsWith(normalizedPrefix)) {
            return key;
        }
        return key.substring(normalizedPrefix.length());
    }

    private static String normalizePrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return "";
        }
        String normalized = prefix.trim();
        while (normalized.endsWith("*")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
