package com.playdata.calen.common.media;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    public Optional<ThumbnailContent> createThumbnail(Resource resource, String contentType, Integer requestedWidth) {
        if (resource == null || contentType == null || !contentType.startsWith("image/")) {
            return Optional.empty();
        }

        int width = normalizeWidth(requestedWidth);

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

            return Optional.of(new ThumbnailContent(outputStream.toByteArray(), outputContentType));
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

    public record ThumbnailContent(byte[] bytes, String contentType) {
    }
}
