package com.playdata.calen.common.media;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.geom.AffineTransform;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ImageThumbnailService {

    private static final int DEFAULT_WIDTH = PreparedThumbnailProfile.PREVIEW.width();
    private static final int MIN_WIDTH = 64;
    private static final int MAX_WIDTH = 1280;
    private static final String CACHE_DIRECTORY_NAME = "calen-thumbnail-cache";
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    private final Path cacheRoot;

    public ImageThumbnailService() {
        this(Path.of(System.getProperty("java.io.tmpdir"), CACHE_DIRECTORY_NAME));
    }

    ImageThumbnailService(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    public Optional<ThumbnailContent> createThumbnail(Resource resource, String contentType, Integer requestedWidth) {
        if (resource == null || contentType == null || !contentType.startsWith("image/")) {
            return Optional.empty();
        }

        int width = normalizeWidth(requestedWidth);
        ThumbnailDescriptor descriptor = buildDescriptor(resource, contentType, width);

        Optional<ThumbnailContent> cachedThumbnail = readFromCache(descriptor);
        if (cachedThumbnail.isPresent()) {
            return cachedThumbnail;
        }

        try (InputStream inputStream = resource.getInputStream()) {
            byte[] sourceBytes = inputStream.readAllBytes();
            Optional<PreparedThumbnailContent> generated = createPreparedThumbnails(sourceBytes, contentType, List.of(width)).stream()
                    .findFirst();
            if (generated.isEmpty()) {
                return Optional.empty();
            }

            byte[] bytes = generated.get().bytes();
            writeToCache(descriptor, bytes);
            return Optional.of(new ThumbnailContent(bytes, generated.get().contentType()));
        } catch (IOException exception) {
            log.debug("Failed to create image thumbnail.", exception);
            return Optional.empty();
        }
    }

    public List<PreparedThumbnailContent> createPreparedThumbnails(byte[] sourceBytes, String contentType, List<Integer> requestedWidths) {
        if (sourceBytes == null || sourceBytes.length == 0 || contentType == null || !contentType.startsWith("image/") || requestedWidths == null || requestedWidths.isEmpty()) {
            return List.of();
        }

        try {
            BufferedImage sourceImage = ImageIO.read(new ByteArrayInputStream(sourceBytes));
            if (sourceImage == null) {
                return List.of();
            }

            BufferedImage orientedImage = applyExifOrientation(sourceImage, resolveExifOrientation(sourceBytes));
            if (orientedImage == null) {
                return List.of();
            }

            boolean keepAlpha = preservesAlpha(contentType);
            String outputFormat = keepAlpha ? "png" : "jpg";
            String outputContentType = keepAlpha ? "image/png" : "image/jpeg";
            String fileExtension = keepAlpha ? "png" : "jpg";
            LinkedHashSet<Integer> normalizedWidths = requestedWidths.stream()
                    .filter(width -> width != null)
                    .map(this::normalizeWidth)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

            return normalizedWidths.stream()
                    .filter(width -> orientedImage.getWidth() > width)
                    .map(width -> createPreparedThumbnail(
                            orientedImage,
                            width,
                            keepAlpha,
                            outputFormat,
                            outputContentType,
                            fileExtension,
                            PreparedThumbnailProfile.jpegQualityForWidth(width)
                    ))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException exception) {
            log.debug("Failed to create prepared thumbnails.", exception);
            return List.of();
        }
    }

    private int normalizeWidth(Integer requestedWidth) {
        if (requestedWidth == null) {
            return DEFAULT_WIDTH;
        }
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, requestedWidth));
    }

    private boolean preservesAlpha(String contentType) {
        String normalized = contentType.trim().toLowerCase();
        return normalized.contains("png") || normalized.contains("gif") || normalized.contains("webp");
    }

    int resolveExifOrientation(byte[] sourceBytes) {
        if (sourceBytes == null || sourceBytes.length == 0) {
            return 1;
        }

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(sourceBytes)) {
            ExifIFD0Directory exifDirectory = ImageMetadataReader.readMetadata(inputStream)
                    .getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exifDirectory == null) {
                return 1;
            }

            Integer orientation = exifDirectory.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
            return orientation == null ? 1 : orientation;
        } catch (Exception exception) {
            return 1;
        }
    }

    BufferedImage applyExifOrientation(BufferedImage sourceImage, int orientation) {
        if (sourceImage == null) {
            return null;
        }

        int width = sourceImage.getWidth();
        int height = sourceImage.getHeight();
        AffineTransform transform = new AffineTransform();
        int targetWidth = width;
        int targetHeight = height;

        switch (orientation) {
            case 2 -> {
                transform.scale(-1.0, 1.0);
                transform.translate(-width, 0.0);
            }
            case 3 -> {
                transform.translate(width, height);
                transform.rotate(Math.PI);
            }
            case 4 -> {
                transform.scale(1.0, -1.0);
                transform.translate(0.0, -height);
            }
            case 6 -> {
                targetWidth = height;
                targetHeight = width;
                transform.translate(height, 0.0);
                transform.rotate(Math.PI / 2.0);
            }
            case 8 -> {
                targetWidth = height;
                targetHeight = width;
                transform.translate(0.0, width);
                transform.rotate(-Math.PI / 2.0);
            }
            default -> {
                return sourceImage;
            }
        }

        BufferedImage transformedImage = new BufferedImage(
                targetWidth,
                targetHeight,
                sourceImage.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB
        );

        Graphics2D graphics = transformedImage.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.transform(transform);
            graphics.drawImage(sourceImage, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        return transformedImage;
    }

    private Optional<PreparedThumbnailContent> createPreparedThumbnail(
            BufferedImage sourceImage,
            int targetWidth,
            boolean keepAlpha,
            String outputFormat,
            String outputContentType,
            String fileExtension,
            float jpegQuality
    ) {
        int targetHeight = Math.max(1, (int) Math.round((double) sourceImage.getHeight() * targetWidth / sourceImage.getWidth()));
        BufferedImage thumbnail = new BufferedImage(
                targetWidth,
                targetHeight,
                keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB
        );

        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(sourceImage, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            if (!writeThumbnail(thumbnail, keepAlpha, outputFormat, outputStream, jpegQuality)) {
                return Optional.empty();
            }
            return Optional.of(new PreparedThumbnailContent(targetWidth, outputStream.toByteArray(), outputContentType, fileExtension));
        } catch (IOException exception) {
            log.debug("Failed to write prepared thumbnail.", exception);
            return Optional.empty();
        }
    }

    private boolean writeThumbnail(
            BufferedImage thumbnail,
            boolean keepAlpha,
            String outputFormat,
            ByteArrayOutputStream outputStream,
            float jpegQuality
    ) throws IOException {
        if (keepAlpha) {
            return ImageIO.write(thumbnail, outputFormat, outputStream);
        }
        return writeJpeg(thumbnail, outputStream, jpegQuality);
    }

    private boolean writeJpeg(BufferedImage image, ByteArrayOutputStream outputStream, float jpegQuality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        ImageWriter writer = writers.hasNext() ? writers.next() : null;
        if (writer == null) {
            return false;
        }

        try (ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
            writer.setOutput(imageOutputStream);
            ImageWriteParam writeParam = writer.getDefaultWriteParam();
            if (writeParam.canWriteCompressed()) {
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(Math.max(0.1f, Math.min(jpegQuality, 1.0f)));
            }
            writer.write(null, new IIOImage(image, null, null), writeParam);
            imageOutputStream.flush();
            return true;
        } finally {
            writer.dispose();
        }
    }

    private ThumbnailDescriptor buildDescriptor(Resource resource, String contentType, int width) {
        boolean keepAlpha = preservesAlpha(contentType);
        String extension = keepAlpha ? "png" : "jpg";
        String outputContentType = keepAlpha ? "image/png" : "image/jpeg";
        String cacheKey = buildCacheKey(resource, contentType, width, extension);
        return new ThumbnailDescriptor(cacheRoot.resolve(cacheKey + "." + extension), outputContentType);
    }

    private String buildCacheKey(Resource resource, String contentType, int width, String extension) {
        String source = String.join("|",
                resource.getDescription(),
                contentType,
                extension,
                Integer.toString(width),
                Long.toString(readMetadataSafely(resource::contentLength)),
                Long.toString(readMetadataSafely(resource::lastModified))
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX_FORMAT.formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable.", exception);
        }
    }

    private long readMetadataSafely(ResourceLongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (IOException exception) {
            return -1L;
        }
    }

    private Optional<ThumbnailContent> readFromCache(ThumbnailDescriptor descriptor) {
        try {
            if (!Files.exists(descriptor.path()) || Files.size(descriptor.path()) == 0L) {
                return Optional.empty();
            }
            return Optional.of(new ThumbnailContent(Files.readAllBytes(descriptor.path()), descriptor.contentType()));
        } catch (IOException exception) {
            log.debug("Failed to read cached thumbnail.", exception);
            return Optional.empty();
        }
    }

    private void writeToCache(ThumbnailDescriptor descriptor, byte[] bytes) {
        try {
            Files.createDirectories(cacheRoot);
            Path temporaryFile = Files.createTempFile(cacheRoot, "thumbnail-", ".tmp");
            Files.write(temporaryFile, bytes);
            Files.move(temporaryFile, descriptor.path(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            log.debug("Failed to cache thumbnail.", exception);
        }
    }

    @FunctionalInterface
    private interface ResourceLongSupplier {
        long getAsLong() throws IOException;
    }

    private record ThumbnailDescriptor(Path path, String contentType) {
    }

    public record ThumbnailContent(byte[] bytes, String contentType) {
    }

    public record PreparedThumbnailContent(int width, byte[] bytes, String contentType, String fileExtension) {
    }
}
