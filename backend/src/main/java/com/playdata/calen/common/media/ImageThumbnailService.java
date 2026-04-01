package com.playdata.calen.common.media;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
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
import java.util.Optional;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ImageThumbnailService {

    private static final int DEFAULT_WIDTH = 480;
    private static final int MIN_WIDTH = 120;
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
            BufferedImage sourceImage = ImageIO.read(inputStream);
            if (sourceImage == null) {
                return Optional.empty();
            }

            if (sourceImage.getWidth() <= width) {
                return Optional.empty();
            }

            int targetWidth = width;
            int targetHeight = Math.max(1, (int) Math.round((double) sourceImage.getHeight() * targetWidth / sourceImage.getWidth()));
            boolean keepAlpha = preservesAlpha(contentType);

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

            String outputFormat = keepAlpha ? "png" : "jpg";
            String outputContentType = keepAlpha ? "image/png" : "image/jpeg";

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            if (!ImageIO.write(thumbnail, outputFormat, outputStream)) {
                return Optional.empty();
            }

            byte[] bytes = outputStream.toByteArray();
            writeToCache(descriptor, bytes);
            return Optional.of(new ThumbnailContent(bytes, outputContentType));
        } catch (IOException exception) {
            log.debug("Failed to create image thumbnail.", exception);
            return Optional.empty();
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
}
