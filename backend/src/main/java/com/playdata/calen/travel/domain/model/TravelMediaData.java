package com.playdata.calen.travel.domain.model;

import java.math.BigDecimal;
import java.util.List;

public final class TravelMediaData {
    private TravelMediaData() {}
    public record Download(String storagePath, String contentType, String fileName) {}
    public record Access(Long recordId, Long planId) {}
    public record Owned(Long id, Download download, boolean affectsPhotoCluster) {}
    public record Stored(String originalFileName, String storedFileName, String storagePath, String contentType, long fileSize) {}
    public record Gps(BigDecimal latitude, BigDecimal longitude) {}
    public record ThumbnailCandidate(String variant, String contentType, long fileSize) {}
    public record Candidate(String originalFileName, String contentType, long fileSize, List<ThumbnailCandidate> thumbnails) {}
    public record CompletedThumbnail(String variant, String objectKey, String contentType, long fileSize) {}
    public record Completed(String objectKey, String originalFileName, String contentType, long fileSize, List<CompletedThumbnail> thumbnails,
            BigDecimal gpsLatitude, BigDecimal gpsLongitude) {
        @Override
        public String toString() {
            return "Completed[objectKey=<redacted>, metadata=<redacted>]";
        }
    }

    public record ThumbnailTarget(String variant, String method, String uploadUrl, String objectKey, String contentType, long fileSize) {
        @Override
        public String toString() {
            return "ThumbnailTarget[uploadUrl=<redacted>, objectKey=<redacted>]";
        }
    }

    public record Target(String method, String uploadUrl, String objectKey, String storedFileName, String originalFileName, String contentType,
            long fileSize, List<ThumbnailTarget> thumbnails) {
        @Override
        public String toString() {
            return "Target[uploadUrl=<redacted>, objectKey=<redacted>, metadata=<redacted>]";
        }
    }

    public record Prepared(String uploadMode, List<Target> uploads) {}
}
