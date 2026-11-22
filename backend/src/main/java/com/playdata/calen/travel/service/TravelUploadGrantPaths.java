package com.playdata.calen.travel.service;

import com.playdata.calen.common.media.PreparedThumbnailProfile;
import java.util.Locale;

/** Structural validation independent of the current prefix, which may change between deployments. */
public final class TravelUploadGrantPaths {
    private TravelUploadGrantPaths() {}

    public static boolean isMedia(Long owner, Long plan, Long record, String key) {
        return isScopedLeaf(owner, plan, "/" + record + "/", key) && positive(record);
    }

    public static boolean isRoute(Long owner, Long plan, Long route, String key) {
        return isScopedLeaf(owner, plan, "/routes/" + route + "/", key) && positive(route);
    }

    public static boolean isThumbnail(Long owner, Long plan, Long record, String original, String thumbnail) {
        if (!isMedia(owner, plan, record, original) || !safe(thumbnail)) return false;
        int slash = original.lastIndexOf('/');
        String directory = original.substring(0, slash);
        String name = original.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String lower = name.toLowerCase(Locale.ROOT);
        String extension = lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".webp") ? "png" : "jpg";
        return PreparedThumbnailProfile.defaultWidths().stream().anyMatch(width ->
                thumbnail.equals(directory + "/.thumbs-v2/" + width + "/" + stem + "." + extension)
                        || thumbnail.equals(directory + "/.thumbs/" + width + "/" + stem + "." + extension));
    }

    private static boolean isScopedLeaf(Long owner, Long plan, String tail, String key) {
        if (!positive(owner) || !positive(plan) || !safe(key)) return false;
        String marker = "/" + owner + "/" + plan + tail;
        int index = key.lastIndexOf(marker);
        if (index <= 0) return false;
        String prefix = key.substring(0, index);
        String leaf = key.substring(index + marker.length());
        if (leaf.isBlank() || leaf.contains("/") || leaf.equals(".") || leaf.equals("..")) return false;
        for (String part : prefix.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }

    private static boolean positive(Long value) { return value != null && value > 0; }
    private static boolean safe(String key) {
        return key != null && !key.isBlank() && key.length() <= 600 && !key.contains("\\")
                && key.chars().noneMatch(Character::isISOControl);
    }
}
