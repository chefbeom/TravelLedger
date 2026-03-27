package com.playdata.calen.common.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "minio")
@Getter
@Setter
@NoArgsConstructor
public class MinioProperties {

    private String endpoint;
    private String publicEndpoint;
    private String accessKey;
    private String secretKey;
    private String bucket_cloud;
    private String bucket_work;
    private int presignedUrlExpirySeconds = 600;

    public String getEndpoint() {
        return sanitize(endpoint);
    }

    public String getAccessKey() {
        return sanitize(accessKey);
    }

    public String getPublicEndpoint() {
        return sanitize(publicEndpoint);
    }

    public String getSecretKey() {
        return sanitize(secretKey);
    }

    public String getBucket_cloud() {
        return sanitize(bucket_cloud);
    }

    public String getBucket_work() {
        return sanitize(bucket_work);
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            boolean wrappedWithDoubleQuotes = trimmed.startsWith("\"") && trimmed.endsWith("\"");
            boolean wrappedWithSingleQuotes = trimmed.startsWith("'") && trimmed.endsWith("'");
            if (wrappedWithDoubleQuotes || wrappedWithSingleQuotes) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }

        return trimmed;
    }
}
