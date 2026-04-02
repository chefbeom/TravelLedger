package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.playdata.calen.common.config.MinioProperties;
import io.minio.MinioClient;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

        Path thumbnailDirectory = tempDir.resolve(Path.of(stored.storagePath())).getParent().resolve(".thumbs");
        assertThat(Files.exists(thumbnailDirectory)).isFalse();
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
                new MinioProperties(),
                new com.playdata.calen.common.media.ImageThumbnailService()
        );
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
}
