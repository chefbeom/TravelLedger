package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.drive.dto.DriveDtos;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
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

    @Mock
    private ObjectProvider<MinioClient> presignedMinioClientProvider;

    @Mock
    private MinioClient minioClient;

    @Mock
    private MinioClient presignedMinioClient;

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

        assertThatThrownBy(() -> service.initUpload(1L, java.util.Collections.singletonList((DriveDtos.UploadInitRequest) null)))
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


    @Test
    void abortUploadRejectsObjectKeyOutsideOwnerScopeBeforeStorageAccess() {
        DriveStorageService service = newService();
        DriveDtos.UploadAbortRequest request = DriveDtos.UploadAbortRequest.builder()
                .finalObjectKey("travel-media/2/foreign.png")
                .build();

        assertThatThrownBy(() -> service.abortUpload(1L, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Upload object key is outside the current user drive scope.");

        verify(minioClientProvider, never()).getIfAvailable();
    }

    @Test
    void initUploadGeneratesPresignedUrlWithoutBucketProbe() throws Exception {
        DriveStorageService service = newService();
        DriveDtos.UploadInitRequest request = DriveDtos.UploadInitRequest.builder()
                .fileOriginName("family.png")
                .fileFormat("png")
                .contentType("image/png")
                .fileSize(100L)
                .build();
        when(minioClientProvider.getIfAvailable()).thenReturn(minioClient);
        when(presignedMinioClientProvider.getIfAvailable()).thenReturn(presignedMinioClient);
        when(presignedMinioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("https://storage.example.com/minio/drive-bucket/drive/1/family.png?X-Amz-Signature=public");

        List<DriveDtos.UploadChunkResponse> responses = service.initUpload(1L, List.of(request));

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).presignedUploadUrl())
                .isEqualTo("https://storage.example.com/minio/drive-bucket/drive/1/family.png?X-Amz-Signature=public");
        verify(minioClient, never()).bucketExists(any(BucketExistsArgs.class));
        verify(presignedMinioClient).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        verify(minioClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    void generateDownloadUrlUsesPublicClientUrlWithoutRewritingIt() throws Exception {
        DriveStorageService service = newService();
        when(minioClientProvider.getIfAvailable()).thenReturn(minioClient);
        when(presignedMinioClientProvider.getIfAvailable()).thenReturn(presignedMinioClient);
        String publicUrl = "https://storage.example.com/minio/drive-bucket/drive/1/family.png?X-Amz-Signature=download";
        when(presignedMinioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn(publicUrl);

        assertThat(service.generateDownloadUrl("drive/1/family.png")).isEqualTo(publicUrl);
        verify(presignedMinioClient).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
        verify(minioClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    void objectExistsReportsStorageConnectionFailuresAsServiceUnavailable() throws Exception {
        DriveStorageService service = newService();
        when(minioClientProvider.getIfAvailable()).thenReturn(minioClient);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(new RuntimeException("network down"));

        assertThatThrownBy(() -> service.objectExists("drive/1/family.png"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("드라이브 저장소에 연결할 수 없습니다. 관리자 페이지에서 MinIO 상태와 환경변수를 확인해 주세요.");
    }
    private DriveStorageService newService() {
        MinioProperties properties = new MinioProperties();
        properties.setEndpoint("http://minio:9000");
        properties.setPublicEndpoint("https://storage.example.com/minio");
        properties.setAccessKey("access");
        properties.setSecretKey("secret");
        properties.setBucket_cloud("drive-bucket");
        return new DriveStorageService(minioClientProvider, presignedMinioClientProvider, properties);
    }
}