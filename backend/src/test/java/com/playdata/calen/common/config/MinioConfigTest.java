package com.playdata.calen.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import java.net.URI;
import org.junit.jupiter.api.Test;

class MinioConfigTest {

    @Test
    void clientsSignAgainstTheirConfiguredEndpointsWithoutNetworkCall() throws Exception {
        MinioProperties properties = new MinioProperties();
        properties.setEndpoint("http://internal-minio.example:9000");
        properties.setPublicEndpoint("https://public-minio.example:9443");
        properties.setAccessKey("test-access-key");
        properties.setSecretKey("test-secret-key");
        properties.setBucket_cloud("test-bucket");

        MinioConfig config = new MinioConfig(properties);
        MinioClient internalClient = config.minioClient();
        MinioClient publicClient = config.minioPresignedClient();

        URI internalUrl = URI.create(internalClient.getPresignedObjectUrl(presignedGetArgs()));
        URI publicUrl = URI.create(publicClient.getPresignedObjectUrl(presignedGetArgs()));

        assertThat(internalUrl.getScheme()).isEqualTo("http");
        assertThat(internalUrl.getHost()).isEqualTo("internal-minio.example");
        assertThat(internalUrl.getPort()).isEqualTo(9000);

        assertThat(publicUrl.getScheme()).isEqualTo("https");
        assertThat(publicUrl.getHost()).isEqualTo("public-minio.example");
        assertThat(publicUrl.getPort()).isEqualTo(9443);
    }

    private GetPresignedObjectUrlArgs presignedGetArgs() {
        return GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket("test-bucket")
                .region("us-east-1")
                .object("sample.txt")
                .build();
    }
}
