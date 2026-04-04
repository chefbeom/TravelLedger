package com.playdata.calen.common.media;

import java.util.List;

public final class PreparedThumbnailProfile {

    public static final PreparedThumbnailSpec PIN = new PreparedThumbnailSpec("pin", 96, 0.72f);
    public static final PreparedThumbnailSpec MINI = new PreparedThumbnailSpec("mini", 240, 0.78f);
    public static final PreparedThumbnailSpec PREVIEW = new PreparedThumbnailSpec("preview", 480, 0.82f);
    public static final PreparedThumbnailSpec DETAIL = new PreparedThumbnailSpec("detail", 960, 0.86f);

    private static final List<PreparedThumbnailSpec> DEFAULT_SPECS = List.of(
            PIN,
            MINI,
            PREVIEW,
            DETAIL
    );
    private static final float DEFAULT_JPEG_QUALITY = PREVIEW.jpegQuality();

    private PreparedThumbnailProfile() {
    }

    public static List<PreparedThumbnailSpec> defaultSpecs() {
        return DEFAULT_SPECS;
    }

    public static List<Integer> defaultWidths() {
        return DEFAULT_SPECS.stream()
                .map(PreparedThumbnailSpec::width)
                .toList();
    }

    public static int selectWidth(Integer requestedWidth) {
        int normalizedWidth = requestedWidth == null ? PREVIEW.width() : Math.max(PIN.width(), requestedWidth);
        for (PreparedThumbnailSpec spec : DEFAULT_SPECS) {
            if (normalizedWidth <= spec.width()) {
                return spec.width();
            }
        }
        return DETAIL.width();
    }

    public static float jpegQualityForWidth(int width) {
        return DEFAULT_SPECS.stream()
                .filter(spec -> spec.width() == width)
                .findFirst()
                .map(PreparedThumbnailSpec::jpegQuality)
                .orElse(DEFAULT_JPEG_QUALITY);
    }

    public record PreparedThumbnailSpec(
            String key,
            int width,
            float jpegQuality
    ) {
    }
}
