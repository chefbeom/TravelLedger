package com.playdata.calen.familyalbum.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.common.config.MinioProperties;
import io.minio.MinioClient;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

class FamilyMediaStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void preparePresignedUploadUsesPublicClientUrl() throws Exception {
        MinioClient internalClient = mock(MinioClient.class);
        MinioClient publicClient = mock(MinioClient.class);
        when(publicClient.getPresignedObjectUrl(any())).thenReturn(
                "https://storage.example.com/family-bucket/family-media/1/2/photo.jpg?signature=public"
        );

        FamilyMediaStorageService service = createService(internalClient, publicClient);

        List<FamilyMediaStorageService.PresignedFamilyMediaUpload> uploads =
                service.preparePresignedUploads(
                        1L,
                        2L,
                        List.of(new FamilyMediaStorageService.FamilyMediaUploadCandidate(
                                "photo.jpg",
                                "image/jpeg",
                                256L
                        ))
                );

        assertThat(uploads).singleElement()
                .extracting(FamilyMediaStorageService.PresignedFamilyMediaUpload::uploadUrl)
                .isEqualTo("https://storage.example.com/family-bucket/family-media/1/2/photo.jpg?signature=public");
        verify(publicClient).getPresignedObjectUrl(any());
    }

    private FamilyMediaStorageService createService(
            MinioClient internalClient,
            MinioClient publicClient
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MinioClient> internalProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<MinioClient> publicProvider = mock(ObjectProvider.class);
        when(internalProvider.getIfAvailable()).thenReturn(internalClient);
        when(publicProvider.getIfAvailable()).thenReturn(publicClient);

        MinioProperties properties = new MinioProperties();
        properties.setEndpoint("http://localhost:9000");
        properties.setPublicEndpoint("https://storage.example.com");
        properties.setAccessKey("access-key");
        properties.setSecretKey("secret-key");
        properties.setBucket_cloud("family-bucket");

        return new FamilyMediaStorageService(
                tempDir.toString(),
                "family-media",
                internalProvider,
                publicProvider,
                properties,
                new com.playdata.calen.common.media.ImageThumbnailService()
        );
    }
}
