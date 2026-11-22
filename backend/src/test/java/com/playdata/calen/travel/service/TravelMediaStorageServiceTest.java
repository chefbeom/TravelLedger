package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.media.ExifTestImages;
import com.playdata.calen.common.media.PreparedThumbnailProfile;
import io.minio.MinioClient;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectResponse;
import java.util.List;
import org.mockito.ArgumentCaptor;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;

class TravelMediaStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storesPreparedThumbnailsAlongsideOriginalImage() throws Exception {
        TravelMediaStorageService service = createLocalStorageService();

        TravelMediaStorageService.StoredTravelMedia stored = service.store(
                1L,
                2L,
                3L,
                new MockMultipartFile("file", "beach.png", "image/png", createPngBytes(1600, 900))
        );

        assertThat(Files.exists(tempDir.resolve(stored.storagePath()))).isTrue();
        assertThat(service.loadPreparedThumbnail(stored.storagePath(), stored.contentType(), 320)).isNotNull();
        assertThat(service.loadPreparedThumbnail(stored.storagePath(), stored.contentType(), 480)).isNotNull();
        assertThat(service.loadPreparedThumbnail(stored.storagePath(), stored.contentType(), 960)).isNotNull();
    }

    @Test
    void storesRouteGpxWithoutGeneratingPreparedThumbnails() throws Exception {
        TravelMediaStorageService service = createLocalStorageService();

        TravelMediaStorageService.StoredTravelMedia stored = service.storeRouteGpx(
                1L,
                2L,
                7L,
                new MockMultipartFile(
                        "file",
                        "route.gpx",
                        "application/gpx+xml",
                        """
                        <gpx version="1.1" creator="test">
                          <trk><trkseg>
                            <trkpt lat="37.5665" lon="126.9780"/>
                            <trkpt lat="37.5651" lon="126.9895"/>
                          </trkseg></trk>
                        </gpx>
                        """.getBytes(StandardCharsets.UTF_8)
                )
        );

        assertThat(Files.exists(tempDir.resolve(stored.storagePath()))).isTrue();
        assertThat(service.loadPreparedThumbnail(stored.storagePath(), stored.contentType(), 480)).isNull();

        Path thumbnailDirectory = tempDir.resolve(Path.of(stored.storagePath())).getParent().resolve(".thumbs-v2");
        assertThat(Files.exists(thumbnailDirectory)).isFalse();
    }

    @Test
    void ensurePreparedThumbnailsReportsAlreadyPresentWhenPreparedSetExists() throws Exception {
        TravelMediaStorageService service = createLocalStorageService();

        TravelMediaStorageService.StoredTravelMedia stored = service.store(
                1L,
                2L,
                3L,
                new MockMultipartFile("file", "sunrise.jpg", "image/jpeg", createJpegBytes(1600, 900))
        );

        TravelMediaStorageService.ThumbnailPreparationStatus status =
                service.ensurePreparedThumbnails(stored.storagePath(), stored.contentType());

        assertThat(status).isEqualTo(TravelMediaStorageService.ThumbnailPreparationStatus.ALREADY_PRESENT);
    }

    @Test
    void ensurePreparedThumbnailsRecreatesMissingPreparedVariant() throws Exception {
        TravelMediaStorageService service = createLocalStorageService();

        TravelMediaStorageService.StoredTravelMedia stored = service.store(
                1L,
                2L,
                3L,
                new MockMultipartFile("file", "sunrise.jpg", "image/jpeg", createJpegBytes(1600, 900))
        );

        Path missingThumbnailPath = thumbnailPathFor(stored.storagePath(), stored.contentType(), PreparedThumbnailProfile.PIN.width());
        Files.deleteIfExists(missingThumbnailPath);
        assertThat(Files.exists(missingThumbnailPath)).isFalse();

        TravelMediaStorageService.ThumbnailPreparationStatus status =
                service.ensurePreparedThumbnails(stored.storagePath(), stored.contentType());

        assertThat(status).isEqualTo(TravelMediaStorageService.ThumbnailPreparationStatus.CREATED);
        assertThat(Files.exists(missingThumbnailPath)).isTrue();
    }

    @Test
    void completePresignedUploadRejectsObjectKeyOutsideOwnerRecordScopeBeforeMinioStat() {
        MinioClient minioClient = mock(MinioClient.class);
        TravelMediaStorageService service = createPresignedStorageService(minioClient);

        assertThatThrownBy(() -> service.completePresignedUpload(
                        1L,
                        2L,
                        3L,
                        new TravelMediaStorageService.CompletedUpload(
                                "travel-media/9/2/3/receipt.pdf",
                                "receipt.pdf",
                                "application/pdf",
                                128L,
                                List.of(),
                                false
                        )
                ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid uploaded file path.");

        verifyNoInteractions(minioClient);
    }

    @Test
    void completePresignedUploadRejectsAmbiguousObjectKeyBeforeMinioStat() {
        MinioClient minioClient = mock(MinioClient.class);
        TravelMediaStorageService service = createPresignedStorageService(minioClient);

        assertThatThrownBy(() -> service.completePresignedUpload(
                        1L,
                        2L,
                        3L,
                        new TravelMediaStorageService.CompletedUpload(
                                "travel-media/1/2/3/../4/receipt.pdf",
                                "receipt.pdf",
                                "application/pdf",
                                128L,
                                List.of(),
                                false
                        )
                ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Invalid uploaded file path.");

        verifyNoInteractions(minioClient);
    }

    @Test
    void preparePresignedUploadUsesPublicClientUrlWithoutRewritingIt() throws Exception {
        MinioClient internalMinioClient = mock(MinioClient.class);
        MinioClient publicMinioClient = mock(MinioClient.class);
        TravelMediaStorageService service = createPresignedStorageService(internalMinioClient, publicMinioClient);
        String publicUrl = "https://storage.example.com/minio/travel-bucket/travel-media/1/2/3/receipt.pdf?X-Amz-Signature=public";
        when(publicMinioClient.getPresignedObjectUrl(org.mockito.ArgumentMatchers.any()))
                .thenReturn(publicUrl);

        List<TravelMediaStorageService.PresignedUpload> uploads = service.preparePresignedUploads(
                1L,
                2L,
                3L,
                List.of(new TravelMediaStorageService.UploadCandidate(
                        "receipt.pdf",
                        "application/pdf",
                        128L,
                        List.of()
                ))
        );

        assertThat(uploads).singleElement().extracting(TravelMediaStorageService.PresignedUpload::uploadUrl)
                .isEqualTo(publicUrl);
        verify(publicMinioClient).getPresignedObjectUrl(org.mockito.ArgumentMatchers.any());
        verify(internalMinioClient, never()).getPresignedObjectUrl(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void preparePresignedRouteGpxUploadUsesPublicClientUrl() throws Exception {
        MinioClient internalMinioClient = mock(MinioClient.class);
        MinioClient publicMinioClient = mock(MinioClient.class);
        TravelMediaStorageService service = createPresignedStorageService(internalMinioClient, publicMinioClient);
        String publicUrl = "https://storage.example.com/minio/travel-bucket/travel-media/1/2/routes/7/route.gpx?signature=public";
        when(publicMinioClient.getPresignedObjectUrl(org.mockito.ArgumentMatchers.any()))
                .thenReturn(publicUrl);

        List<TravelMediaStorageService.PresignedRouteGpxUpload> uploads =
                service.preparePresignedRouteGpxUploads(
                        1L,
                        2L,
                        7L,
                        List.of(new TravelMediaStorageService.RouteGpxUploadCandidate(
                                "route.gpx",
                                "application/gpx+xml",
                                256L
                        ))
                );

        assertThat(uploads).singleElement()
                .extracting(TravelMediaStorageService.PresignedRouteGpxUpload::uploadUrl)
                .isEqualTo(publicUrl);
        verify(publicMinioClient).getPresignedObjectUrl(org.mockito.ArgumentMatchers.any());
        verify(internalMinioClient, never()).getPresignedObjectUrl(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sdkSignedUrlsAndAllSupportedThumbnailFormatsAreAcceptedByGrantTracking() {
        // Presigning with an explicit region is local: this does not contact an object-storage server.
        var publicClient = MinioClient.builder().endpoint("https://storage.example.com")
                .credentials("synthetic-access", "synthetic-secret").region("us-east-1").build();
        var service = createPresignedStorageService(mock(MinioClient.class), publicClient);
        var repository = mock(com.playdata.calen.travel.repository.TravelUploadGrantRepository.class);
        var properties = new MinioProperties(); properties.setBucket_cloud("travel-bucket"); properties.setPresignedUrlExpirySeconds(600);
        var registry = new TravelUploadGrantRegistry(repository, properties);
        org.springframework.test.util.ReflectionTestUtils.setField(registry, "configuredPrefix", "travel-media");
        var adapter = new com.playdata.calen.travel.adapter.out.storage.TravelMediaStorageAdapter(service, registry);
        for (String[] type : List.of(new String[]{"JPG", "image/jpeg"}, new String[]{"jpeg", "image/jpeg"},
                new String[]{"png", "image/png"}, new String[]{"gif", "image/gif"}, new String[]{"webp", "image/webp"},
                new String[]{"bmp", "image/bmp"}, new String[]{"pdf", "application/pdf"})) {
            String thumbType = List.of("image/png", "image/gif", "image/webp").contains(type[1]) ? "image/png" : "image/jpeg";
            var thumbnails = type[1].startsWith("image/") ? PreparedThumbnailProfile.defaultSpecs().stream()
                    .map(spec -> new com.playdata.calen.travel.domain.model.TravelMediaData.ThumbnailCandidate(spec.key(), thumbType, 1))
                    .toList() : List.<com.playdata.calen.travel.domain.model.TravelMediaData.ThumbnailCandidate>of();
            var upload = adapter.prepare(1L, 2L, 3L, List.of(new com.playdata.calen.travel.domain.model.TravelMediaData.Candidate(
                    "photo." + type[0], type[1], 1, thumbnails))).get(0);
            assertThat(upload.uploadUrl()).startsWith("https://storage.example.com/");
        }
        var routes = new com.playdata.calen.travel.adapter.out.storage.TravelRouteUploadAdapter(service, registry);
        routes.prepare(1L, 2L, 7L, List.of(new com.playdata.calen.travel.domain.model.TravelRouteUploadData.Candidate("route.gpx", "xml", 1)));
        verify(repository, org.mockito.Mockito.times(32)).save(org.mockito.ArgumentMatchers.any());
    }


    @Test
    void completePresignedGpxRejectsForeignOwnerPlanRouteAndAmbiguousKeysBeforeStat() throws Exception {
        MinioClient client=mock(MinioClient.class);
        TravelMediaStorageService service=createPresignedStorageService(client);
        for(String key:List.of("travel-media/99/2/routes/7/route.gpx","travel-media/1/99/routes/7/route.gpx",
                "travel-media/1/2/routes/99/route.gpx","travel-media/1/2/route.gpx","travel-media/1/2/routes/7/../route.gpx",
                "travel-media/1/2/routes/7//route.gpx"," travel-media/1/2/routes/7/route.gpx","travel-media/1/2/routes/7/./route.gpx")) {
            assertThatThrownBy(()->service.completePresignedRouteGpxUpload(1L,2L,7L,
                    new TravelMediaStorageService.CompletedRouteGpxUpload(key,"route.gpx","application/gpx+xml",1)))
                    .isInstanceOf(com.playdata.calen.common.exception.BadRequestException.class).hasMessage("Invalid uploaded file path.");
        }
        verify(client,never()).statObject(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void abortPresignedGpxKeepsOwnerScopeAndExistingBestEffortSequentialOrder() throws Exception {
        MinioClient client=mock(MinioClient.class);
        TravelMediaStorageService service=createPresignedStorageService(client);
        service.abortPresignedRouteGpxUploads(1L,2L,7L,null);
        assertThatThrownBy(()->service.abortPresignedRouteGpxUploads(1L,2L,7L,List.of(
                "travel-media/1/2/routes/7/valid.gpx","travel-media/2/2/routes/7/foreign.gpx")))
                .hasMessage("Invalid uploaded file path.");
        verify(client).removeObject(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void gpxPortBridgeKeepsValidationBeforeLazyReadAndExistingIoErrorTranslation() throws Exception {
        TravelMediaStorageService service=createLocalStorageService();
        var adapter=new com.playdata.calen.travel.adapter.out.storage.TravelRouteUploadAdapter(service, mock(TravelUploadGrantRegistry.class));
        var input=mock(com.playdata.calen.travel.application.port.out.TravelRouteUploadPort.Upload.class);
        when(input.fileName()).thenReturn("not-gpx.png");when(input.size()).thenReturn(1L);
        when(input.bytes()).thenThrow(new java.io.IOException("synthetic read failure"));
        assertThatThrownBy(()->adapter.store(1L,2L,7L,input)).hasMessage("Only .gpx files are allowed.");
        verify(input,never()).bytes();
        when(input.fileName()).thenReturn("route.gpx");
        assertThatThrownBy(()->adapter.store(1L,2L,7L,input)).hasMessage("Failed to read the uploaded file.");
        verify(input).bytes();
    }

    @Test
    void loadThumbnailRegeneratesOrientedThumbnailWhenOnlyLegacyExists() throws Exception {
        TravelMediaStorageService service = createLocalStorageService();
        TravelMediaStorageService.StoredTravelMedia stored = storeOrientedPhoto(service);
        deleteVersionedThumbnails(stored);
        Path legacy = legacyThumbnailPathFor(stored.storagePath(), 480);
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, ExifTestImages.markerJpeg(480, 270));

        var thumbnail = service.loadThumbnail(stored.storagePath(), stored.contentType(), 480);

        assertThat(thumbnail).isNotNull();
        BufferedImage image;
        try (var stream = thumbnail.resource().getInputStream()) {
            image = ImageIO.read(stream);
        }
        assertThat(image.getHeight()).isGreaterThan(image.getWidth());
        assertThat(Files.exists(thumbnailPathFor(stored.storagePath(), stored.contentType(), 480))).isTrue();
    }

    @Test
    void loadThumbnailFallsBackToLegacyWhenOnDemandGenerationIsBusy() throws Exception {
        TravelMediaStorageService service = createLocalStorageService();
        TravelMediaStorageService.StoredTravelMedia stored = storeOrientedPhoto(service);
        deleteVersionedThumbnails(stored);
        byte[] legacyBytes = ExifTestImages.markerJpeg(480, 270);
        Path legacy = legacyThumbnailPathFor(stored.storagePath(), 480);
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, legacyBytes);

        int drained = service.onDemandThumbnailPermitsForTest().drainPermits();
        try {
            var thumbnail = service.loadThumbnail(stored.storagePath(), stored.contentType(), 480);

            assertThat(thumbnail).isNotNull();
            try (var stream = thumbnail.resource().getInputStream()) {
                assertThat(stream.readAllBytes()).isEqualTo(legacyBytes);
            }
            assertThat(Files.exists(thumbnailPathFor(stored.storagePath(), stored.contentType(), 480))).isFalse();
        } finally {
            service.onDemandThumbnailPermitsForTest().release(drained);
        }
    }

    @Test
    void deleteImageWithThumbnailsRemovesLegacyAndVersionedThumbnails() throws Exception {
        TravelMediaStorageService service = createLocalStorageService();
        TravelMediaStorageService.StoredTravelMedia stored = storeOrientedPhoto(service);
        for (int width : PreparedThumbnailProfile.defaultWidths()) {
            Path legacy = legacyThumbnailPathFor(stored.storagePath(), width);
            Files.createDirectories(legacy.getParent());
            Files.write(legacy, new byte[] {1});
        }

        service.deleteImageWithThumbnailsQuietly(stored.storagePath(), stored.contentType());

        assertThat(Files.exists(tempDir.resolve(stored.storagePath()))).isFalse();
        for (int width : PreparedThumbnailProfile.defaultWidths()) {
            assertThat(Files.exists(legacyThumbnailPathFor(stored.storagePath(), width))).isFalse();
            assertThat(Files.exists(thumbnailPathFor(stored.storagePath(), stored.contentType(), width))).isFalse();
        }
    }

    @Test
    void ensurePreparedThumbnailsCreatesVersionedSetWhenOnlyLegacyExists() throws Exception {
        TravelMediaStorageService service = createLocalStorageService();
        TravelMediaStorageService.StoredTravelMedia stored = storeOrientedPhoto(service);
        deleteVersionedThumbnails(stored);
        Path legacy = legacyThumbnailPathFor(stored.storagePath(), 480);
        Files.createDirectories(legacy.getParent());
        Files.write(legacy, ExifTestImages.markerJpeg(480, 270));

        var status = service.ensurePreparedThumbnails(stored.storagePath(), stored.contentType());

        assertThat(status).isEqualTo(TravelMediaStorageService.ThumbnailPreparationStatus.CREATED);
        for (int width : PreparedThumbnailProfile.defaultWidths()) {
            assertThat(Files.exists(thumbnailPathFor(stored.storagePath(), stored.contentType(), width))).isTrue();
        }
    }

    @Test
    void completePresignedUploadDiscardsClientThumbnailsWithoutOrientationMarker() throws Exception {
        MinioClient client = mock(MinioClient.class);
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(5L);
        when(client.statObject(org.mockito.ArgumentMatchers.any())).thenReturn(stat);
        TravelMediaStorageService service = createPresignedStorageService(client);

        service.completePresignedUpload(1L, 2L, 3L, completedPhotoUpload(false));

        ArgumentCaptor<RemoveObjectArgs> removed = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(client, org.mockito.Mockito.times(4)).removeObject(removed.capture());
        assertThat(removed.getAllValues()).extracting(RemoveObjectArgs::object).containsExactlyInAnyOrder(
                "travel-media/1/2/3/.thumbs-v2/96/photo.jpg",
                "travel-media/1/2/3/.thumbs-v2/240/photo.jpg",
                "travel-media/1/2/3/.thumbs-v2/480/photo.jpg",
                "travel-media/1/2/3/.thumbs-v2/960/photo.jpg");
    }

    @Test
    void completePresignedUploadKeepsClientThumbnailsWithOrientationMarker() throws Exception {
        MinioClient client = mock(MinioClient.class);
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(5L);
        when(client.statObject(org.mockito.ArgumentMatchers.any())).thenReturn(stat);
        TravelMediaStorageService service = createPresignedStorageService(client);

        service.completePresignedUpload(1L, 2L, 3L, completedPhotoUpload(true));

        verify(client, never()).removeObject(org.mockito.ArgumentMatchers.any());
    }

    private TravelMediaStorageService.CompletedUpload completedPhotoUpload(boolean orientedThumbnails) {
        List<TravelMediaStorageService.CompletedPreparedThumbnailUpload> thumbnails = PreparedThumbnailProfile.defaultSpecs().stream()
                .map(spec -> new TravelMediaStorageService.CompletedPreparedThumbnailUpload(
                        spec.key(), "travel-media/1/2/3/.thumbs-v2/" + spec.width() + "/photo.jpg", "image/jpeg", 5L))
                .toList();
        return new TravelMediaStorageService.CompletedUpload(
                "travel-media/1/2/3/photo.jpg", "photo.jpg", "image/jpeg", 5L, thumbnails, orientedThumbnails);
    }

    private TravelMediaStorageService.StoredTravelMedia storeOrientedPhoto(TravelMediaStorageService service) throws Exception {
        byte[] bytes = ExifTestImages.withOrientation(ExifTestImages.markerJpeg(1600, 900), 6, false);
        return service.store(1L, 2L, 3L, new MockMultipartFile("file", "portrait.jpg", "image/jpeg", bytes));
    }

    private void deleteVersionedThumbnails(TravelMediaStorageService.StoredTravelMedia stored) throws Exception {
        for (int width : PreparedThumbnailProfile.defaultWidths()) {
            Files.deleteIfExists(thumbnailPathFor(stored.storagePath(), stored.contentType(), width));
        }
    }

    private Path legacyThumbnailPathFor(String storagePath, int width) {
        Path relativePath = Path.of(storagePath);
        String fileName = relativePath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        return tempDir.resolve(relativePath.getParent().resolve(".thumbs").resolve(String.valueOf(width)).resolve(baseName + ".jpg"));
    }

    private TravelMediaStorageService createLocalStorageService() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MinioClient> minioProvider = mock(ObjectProvider.class);
        when(minioProvider.getIfAvailable()).thenReturn(null);
        return new TravelMediaStorageService(
                tempDir.toString(),
                "travel-media",
                false,
                minioProvider,
                minioProvider,
                new MinioProperties(),
                new com.playdata.calen.common.media.ImageThumbnailService()
        );
    }

    private TravelMediaStorageService createPresignedStorageService(MinioClient minioClient) {
        return createPresignedStorageService(minioClient, minioClient);
    }

    private TravelMediaStorageService createPresignedStorageService(
            MinioClient internalMinioClient,
            MinioClient publicMinioClient
    ) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MinioClient> minioProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<MinioClient> publicMinioProvider = mock(ObjectProvider.class);
        when(minioProvider.getIfAvailable()).thenReturn(internalMinioClient);
        when(publicMinioProvider.getIfAvailable()).thenReturn(publicMinioClient);
        MinioProperties minioProperties = new MinioProperties();
        minioProperties.setEndpoint("http://localhost:9000");
        minioProperties.setPublicEndpoint("https://storage.example.com/minio");
        minioProperties.setAccessKey("access-key");
        minioProperties.setSecretKey("secret-key");
        minioProperties.setBucket_cloud("travel-bucket");
        return new TravelMediaStorageService(
                tempDir.toString(),
                "travel-media",
                true,
                minioProvider,
                publicMinioProvider,
                minioProperties,
                new com.playdata.calen.common.media.ImageThumbnailService()
        );
    }
    private Path thumbnailPathFor(String storagePath, String contentType, int width) {
        Path relativePath = Path.of(storagePath);
        Path directory = relativePath.getParent();
        String fileName = relativePath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        String extension = "image/png".equals(contentType) || "image/gif".equals(contentType) || "image/webp".equals(contentType)
                ? "png"
                : "jpg";

        Path thumbnailRelativePath = directory == null
                ? Path.of(".thumbs-v2", String.valueOf(width), baseName + "." + extension)
                : directory.resolve(".thumbs-v2").resolve(String.valueOf(width)).resolve(baseName + "." + extension);
        return tempDir.resolve(thumbnailRelativePath);
    }

    private byte[] createPngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y += 1) {
            for (int x = 0; x < width; x += 1) {
                image.setRGB(x, y, new Color((x * 255) / width, (y * 255) / height, 180).getRGB());
            }
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return outputStream.toByteArray();
    }

    private byte[] createJpegBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y += 1) {
            for (int x = 0; x < width; x += 1) {
                image.setRGB(x, y, new Color((x * 255) / width, (y * 255) / height, 180).getRGB());
            }
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", outputStream);
        return outputStream.toByteArray();
    }
}
