package com.playdata.calen.common.embedding;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "app.embedding")
@Validated
@Getter
@Setter
public class EmbeddingProperties {

    private boolean enabled = false;
    private String baseUrl = "";
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(180);

    @Min(1)
    @Max(4)
    private int batchSize = 4;

    @AssertTrue(message = "enabled embedding requires an HTTP(S) base URL without credentials, query, fragment or path prefix")
    public boolean isBaseUrlValid() {
        if (!enabled) return true;
        if (baseUrl == null || baseUrl.isBlank()) return false;
        try {
            URI uri = URI.create(baseUrl.strip());
            String path = uri.getRawPath();
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank()
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (path == null || path.isEmpty() || "/".equals(path))
                    && (uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @AssertTrue(message = "enabled embedding requires a nonempty API key without spaces or control characters")
    public boolean isApiKeyValid() {
        return !enabled || (apiKey != null && !apiKey.isBlank()
                && apiKey.chars().allMatch(value -> value >= 0x21 && value <= 0x7e));
    }

    @AssertTrue(message = "enabled embedding requires positive connect-timeout and read-timeout")
    public boolean isTimeoutsValid() {
        return !enabled || (isPositive(connectTimeout) && isPositive(readTimeout));
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }
}
