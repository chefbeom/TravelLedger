package com.playdata.calen.common.storage;

import com.playdata.calen.common.exception.ServiceUnavailableException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;

/** Extracts only the signed UTC validity window; URL credentials are never returned or logged. */
public final class SignedUploadWindow {
    private static final long MAX_EXPIRY_SECONDS = 7L * 24 * 60 * 60;
    private static final DateTimeFormatter SIGNED_UTC = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withResolverStyle(ResolverStyle.STRICT);
    private SignedUploadWindow() {}

    public static Window parse(String url, Integer expectedSeconds) {
        try {
            URI uri = URI.create(url);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getRawQuery() == null) throw invalid();
            String signedAt = null, expires = null;
            for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                String name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                if ("X-Amz-Date".equalsIgnoreCase(name)) {
                    if (signedAt != null) throw invalid();
                    signedAt = value;
                } else if ("X-Amz-Expires".equalsIgnoreCase(name)) {
                    if (expires != null) throw invalid();
                    expires = value;
                }
            }
            if (signedAt == null || !signedAt.matches("[0-9]{8}T[0-9]{6}Z") || expires == null
                    || !expires.matches("[0-9]{1,6}")) throw invalid();
            long seconds = Long.parseLong(expires);
            if (seconds < 1 || seconds > MAX_EXPIRY_SECONDS || expectedSeconds == null || seconds != expectedSeconds) throw invalid();
            LocalDateTime start = LocalDateTime.parse(signedAt, SIGNED_UTC);
            return new Window(start, start.plusSeconds(seconds));
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static ServiceUnavailableException invalid() {
        return new ServiceUnavailableException("업로드 주소의 안전한 만료 정보를 확인할 수 없습니다.");
    }

    public record Window(LocalDateTime signedAtUtc, LocalDateTime expiresAtUtc) {}
}
