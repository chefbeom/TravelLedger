package com.playdata.calen.common.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.AbstractResource;

class ImageThumbnailServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReuseCachedThumbnailForRepeatedRequests() throws IOException {
        ImageThumbnailService service = new ImageThumbnailService(tempDir);
        CountingImageResource resource = new CountingImageResource(createPngBytes(1600, 900));

        ImageThumbnailService.ThumbnailContent first = service.createThumbnail(resource, "image/png", 480)
                .orElseThrow();
        ImageThumbnailService.ThumbnailContent second = service.createThumbnail(resource, "image/png", 480)
                .orElseThrow();

        assertThat(resource.openCount()).isEqualTo(1);
        assertThat(second.bytes()).isEqualTo(first.bytes());
        assertThat(second.contentType()).isEqualTo("image/png");
    }

    @Test
    void shouldRotatePortraitOrientationForThumbnailSource() {
        ImageThumbnailService service = new ImageThumbnailService(tempDir);
        BufferedImage source = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB);

        BufferedImage rotated = service.applyExifOrientation(source, 6);

        assertThat(rotated.getWidth()).isEqualTo(900);
        assertThat(rotated.getHeight()).isEqualTo(1600);
    }

    private byte[] createPngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, new Color((x * 255) / width, (y * 255) / height, 180).getRGB());
            }
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return outputStream.toByteArray();
    }

    private static final class CountingImageResource extends AbstractResource {

        private final byte[] bytes;
        private int openCount;

        private CountingImageResource(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public String getDescription() {
            return "counting-image-resource";
        }

        @Override
        public InputStream getInputStream() {
            openCount += 1;
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public long contentLength() {
            return bytes.length;
        }

        @Override
        public long lastModified() {
            return 123456789L;
        }

        private int openCount() {
            return openCount;
        }
    }
}
