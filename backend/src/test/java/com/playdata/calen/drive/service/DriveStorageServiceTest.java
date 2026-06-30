package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.drive.dto.DriveDtos;
import io.minio.MinioClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DriveStorageServiceTest {

    @Mock
    private ObjectProvider<MinioClient> minioClientProvider;

    @Test
    void initUploadRejectsKnownExtensionContentTypeMismatchBeforeStorageAccess() {
        DriveStorageService service = newService();
        DriveDtos.UploadInitRequest request = DriveDtos.UploadInitRequest.builder()
                .fileOriginName("receipt.png")
                .fileFormat("png")
                .contentType("application/pdf")
                .fileSize(100L)
                .build();

        assertThatThrownBy(() -> service.initUpload(1L, List.of(request)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("File extension and content type do not match.");

        verify(minioClientProvider, never()).getIfAvailable();
    }

    @Test
    void initUploadRejectsNullRequestBeforeStorageAccess() {
        DriveStorageService service = newService();

        assertThatThrownBy(() -> service.initUpload(1L, List.of((DriveDtos.UploadInitRequest) null)))
                .isInstanceOf(BadRequestException.class);

        verify(minioClientProvider, never()).getIfAvailable();
    }

    @Test
    void initUploadAllowsGenericOctetStreamForKnownExtension() {
        DriveStorageService service = newService();
        DriveDtos.UploadInitRequest request = DriveDtos.UploadInitRequest.builder()
                .fileOriginName("receipt.png")
                .fileFormat("png")
                .contentType("application/octet-stream")
                .fileSize(100L)
                .build();
        when(minioClientProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.initUpload(1L, List.of(request)))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(minioClientProvider).getIfAvailable();
    }

    private DriveStorageService newService() {
        MinioProperties properties = new MinioProperties();
        properties.setEndpoint("http://minio:9000");
        properties.setAccessKey("access");
        properties.setSecretKey("secret");
        properties.setBucket_cloud("drive-bucket");
        return new DriveStorageService(minioClientProvider, properties);
    }
}