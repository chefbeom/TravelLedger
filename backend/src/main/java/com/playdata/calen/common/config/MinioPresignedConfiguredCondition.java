package com.playdata.calen.common.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

public class MinioPresignedConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String endpoint = context.getEnvironment().getProperty("minio.endpoint");
        String publicEndpoint = context.getEnvironment().getProperty("minio.public-endpoint");
        String accessKey = context.getEnvironment().getProperty("minio.access-key");
        String secretKey = context.getEnvironment().getProperty("minio.secret-key");
        String bucket = context.getEnvironment().getProperty("minio.bucket_cloud");

        return StringUtils.hasText(endpoint)
                && StringUtils.hasText(publicEndpoint)
                && StringUtils.hasText(accessKey)
                && StringUtils.hasText(secretKey)
                && StringUtils.hasText(bucket);
    }
}
