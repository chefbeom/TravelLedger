package com.playdata.calen.common.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Test
    void shouldCreatePreparedThumbnailsForConfiguredWidths() throws IOException {
        ImageThumbnailService service = new ImageThumbnailService(tempDir);

        List<ImageThumbnailService.PreparedThumbnailContent> thumbnails = service.createPreparedThumbnails(
                createPngBytes(1600, 900),
                "image/png",
                List.of(320, 480, 960)
        );

        assertThat(thumbnails).hasSize(3);
        assertThat(thumbnails).extracting(ImageThumbnailService.PreparedThumbnailContent::width)
                .containsExactly(320, 480, 960);
        assertThat(thumbnails).extracting(ImageThumbnailService.PreparedThumbnailContent::contentType)
                .containsOnly("image/png");
        assertThat(thumbnails).allMatch(item -> item.bytes().length > 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void shouldApplyEveryExifOrientationToPreparedThumbnails(int orientation) throws IOException {
        ImageThumbnailService service = new ImageThumbnailService(tempDir);
        byte[] bytes = ExifTestImages.withOrientation(ExifTestImages.markerJpeg(400, 200), orientation, orientation % 2 == 1);

        List<ImageThumbnailService.PreparedThumbnailContent> thumbnails = service.createPreparedThumbnails(bytes, "image/jpeg", List.of(96));

        assertThat(thumbnails).hasSize(1);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(thumbnails.get(0).bytes()));
        boolean quarterTurn = orientation >= 5;
        assertThat(image.getWidth()).isEqualTo(96);
        assertThat(image.getHeight()).isEqualTo(quarterTurn ? 192 : 48);
        String expected = switch (orientation) {
            case 1, 5 -> "TL";
            case 2, 6 -> "TR";
            case 3, 7 -> "BR";
            default -> "BL";
        };
        assertThat(redCorner(image)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 9})
    void shouldTreatOutOfRangeOrientationAsNormal(int orientation) throws IOException {
        ImageThumbnailService service = new ImageThumbnailService(tempDir);
        byte[] bytes = ExifTestImages.withOrientation(ExifTestImages.markerJpeg(400, 200), orientation, true);

        List<ImageThumbnailService.PreparedThumbnailContent> thumbnails = service.createPreparedThumbnails(bytes, "image/jpeg", List.of(96));

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(thumbnails.get(0).bytes()));
        assertThat(image.getWidth()).isEqualTo(96);
        assertThat(image.getHeight()).isEqualTo(48);
        assertThat(redCorner(image)).isEqualTo("TL");
    }

    @Test
    void shouldKeepEveryBucketWhenWiderBucketsAreIncluded() throws IOException {
        ImageThumbnailService service = new ImageThumbnailService(tempDir);
        byte[] png = createPngBytes(800, 400);
        List<Integer> widths = List.of(96, 240, 480, 960);

        List<ImageThumbnailService.PreparedThumbnailContent> wide = service.createPreparedThumbnails(png, "image/png", widths, true);
        List<ImageThumbnailService.PreparedThumbnailContent> legacy = service.createPreparedThumbnails(png, "image/png", widths);

        assertThat(wide).extracting(ImageThumbnailService.PreparedThumbnailContent::width).containsExactly(96, 240, 480, 960);
        BufferedImage largest = ImageIO.read(new ByteArrayInputStream(wide.get(3).bytes()));
        assertThat(largest.getWidth()).isEqualTo(800);
        assertThat(legacy).hasSize(3);
    }

    private static String redCorner(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int inset = 3;
        int[][] points = {{inset, inset}, {w - 1 - inset, inset}, {w - 1 - inset, h - 1 - inset}, {inset, h - 1 - inset}};
        String[] names = {"TL", "TR", "BR", "BL"};
        for (int i = 0; i < points.length; i++) {
            Color c = new Color(image.getRGB(points[i][0], points[i][1]));
            if (c.getRed() > 180 && c.getBlue() < 120) {
                return names[i];
            }
        }
        return "none";
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
