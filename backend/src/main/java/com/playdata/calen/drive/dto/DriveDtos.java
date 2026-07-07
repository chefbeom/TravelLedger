package com.playdata.calen.drive.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

public final class DriveDtos {

    private DriveDtos() {
    }

    @Builder
    public record UploadInitRequest(
            String fileOriginName,
            String fileFormat,
            Long fileSize,
            String contentType,
            Long parentId,
            String relativePath,
            Long lastModified
    ) {
    }

    @Builder
    public record UploadChunkResponse(
            String fileOriginName,
            String fileSaveName,
            String fileFormat,
            Long fileSize,
            String contentType,
            Long parentId,
            String relativePath,
            Long lastModified,
            String presignedUploadUrl,
            Integer presignedUrlExpiresIn,
            String objectKey,
            String finalObjectKey,
            Integer partitionIndex,
            Integer partitionCount,
            Boolean partitioned,
            Boolean uploaded
    ) {
    }

    @Builder
    public record UploadCompleteRequest(
            String fileOriginName,
            String fileFormat,
            Long fileSize,
            String finalObjectKey,
            List<String> chunkObjectKeys,
            Long parentId,
            String relativePath,
            Long lastModified
    ) {
    }

    @Builder
    public record UploadCompleteResponse(
            String fileOriginName,
            String fileSaveName,
            String fileFormat,
            String finalObjectKey
    ) {
    }

    @Builder
    public record UploadAbortRequest(
            String finalObjectKey,
            List<String> chunkObjectKeys
    ) {
    }

    @Builder
    public record ActionResponse(
            String action,
            Integer affectedCount
    ) {
    }

    @Builder
    public record FolderRequest(
            String folderName,
            Long parentId
    ) {
    }

    @Builder
    public record RenameRequest(
            String fileName
    ) {
    }

    @Builder
    public record LockRequest(
            Boolean locked
    ) {
    }

    @Builder
    public record MoveRequest(
            Long targetParentId
    ) {
    }

    @Builder
    public record MoveBatchRequest(
            List<Long> fileIds,
            Long targetParentId
    ) {
    }

    @Builder
    public record RestoreBatchRequest(
            List<Long> fileIds
    ) {
    }

    @Builder
    public record DownloadBatchRequest(
            List<Long> fileIds
    ) {
    }

    @Builder
    public record ListPageRequest(
            Long parentId,
            Integer page,
            Integer size,
            String sortOption,
            String searchQuery,
            String extensionFilter,
            String statusFilter
    ) {
    }

    @Builder
    public record FileItemResponse(
            Long id,
            String fileOriginName,
            String fileSaveName,
            String fileSavePath,
            String fileFormat,
            Long fileSize,
            String nodeType,
            Long parentId,
            Boolean lockedFile,
            Boolean systemManaged,
            String sourceType,
            String sourceReference,
            Boolean sharedFile,
            Boolean trashed,
            LocalDateTime deletedAt,
            LocalDateTime uploadDate,
            LocalDateTime lastModifyDate,
            String downloadUrl,
            String thumbnailUrl,
            Integer presignedUrlExpiresIn,
            String ownerLoginId,
            String ownerDisplayName,
            String folderPath,
            Long shareCount
    ) {
    }

    @Builder
    public record FileBreadcrumbResponse(
            Long id,
            String fileOriginName,
            Long parentId
    ) {
    }

    @Builder
    public record FolderDestinationResponse(
            Long id,
            String fileOriginName,
            Long parentId,
            String path,
            Boolean lockedFile,
            Boolean systemManaged,
            String sourceType,
            String sourceReference
    ) {
    }

    @Builder
    public record FileListPageResponse(
            List<FileItemResponse> fileList,
            List<FileBreadcrumbResponse> breadcrumbs,
            List<String> availableExtensions,
            int totalPage,
            long totalCount,
            int currentPage,
            int currentSize
    ) {
    }

    @Builder
    public record HomeSummaryResponse(
            long driveItemCount,
            long fileCount,
            long folderCount,
            long sharedCount,
            long trashCount,
            long usedBytes,
            List<FileItemResponse> recentFiles
    ) {
    }
    @Builder
    public record FileVersionResponse(
            Long id,
            Long fileId,
            Integer versionNumber,
            String fileOriginName,
            String fileFormat,
            Long fileSize,
            String contentType,
            String source,
            LocalDateTime createdAt
    ) {
    }

    @Builder
    public record DownloadUrlResponse(
            String downloadUrl
    ) {
    }

    @Builder
    public record DownloadLinkCreateRequest(
            Integer expiresInMinutes,
            Integer maxDownloads
    ) {
    }

    @Builder
    public record DownloadLinkResponse(
            Long id,
            String downloadUrl,
            LocalDateTime createdAt,
            LocalDateTime expiresAt,
            Integer maxDownloads,
            Integer downloadCount,
            LocalDateTime lastAccessedAt,
            LocalDateTime revokedAt,
            Boolean available
    ) {
    }

    @Builder
    public record DownloadLinkAccessLogResponse(
            Long id,
            Long linkId,
            Long itemId,
            String status,
            String clientAddress,
            String userAgent,
            LocalDateTime accessedAt
    ) {
    }

    @Builder
    public record ShareRequest(
            List<Long> fileIds,
            String recipientLoginId,
            String permission
    ) {
        public ShareRequest(List<Long> fileIds, String recipientLoginId) {
            this(fileIds, recipientLoginId, null);
        }
    }

    @Builder
    public record ShareInfoResponse(
            Long shareId,
            Long recipientUserId,
            String recipientLoginId,
            String recipientDisplayName,
            String permission,
            LocalDateTime createdAt
    ) {
        public ShareInfoResponse(
                Long shareId,
                Long recipientUserId,
                String recipientLoginId,
                String recipientDisplayName,
                LocalDateTime createdAt
        ) {
            this(shareId, recipientUserId, recipientLoginId, recipientDisplayName, null, createdAt);
        }
    }

    @Builder
    public record SharedFileResponse(
            Long id,
            Long fileId,
            String fileOriginName,
            String fileFormat,
            Long fileSize,
            String ownerLoginId,
            String ownerDisplayName,
            String permission,
            LocalDateTime sharedAt,
            String downloadUrl,
            String thumbnailUrl
    ) {
        public SharedFileResponse(
                Long id,
                Long fileId,
                String fileOriginName,
                String fileFormat,
                Long fileSize,
                String ownerLoginId,
                String ownerDisplayName,
                LocalDateTime sharedAt,
                String downloadUrl,
                String thumbnailUrl
        ) {
            this(id, fileId, fileOriginName, fileFormat, fileSize, ownerLoginId, ownerDisplayName, null, sharedAt, downloadUrl, thumbnailUrl);
        }
    }

    @Builder
    public record SentShareGroupResponse(
            Long fileId,
            String fileOriginName,
            String fileFormat,
            Long fileSize,
            List<ShareInfoResponse> recipients
    ) {
    }

    @Builder
    public record AdminDashboardResponse(
            AdminSummaryResponse summary,
            List<AdminUserResponse> users
    ) {
    }

    @Builder
    public record AdminSummaryResponse(
            long totalUserCount,
            long activeUserCount,
            long totalFileCount,
            long totalFolderCount,
            long totalSharedFileCount,
            long totalTrashedCount,
            long totalUsedBytes
    ) {
    }

    @Builder
    public record AdminUserResponse(
            Long id,
            String loginId,
            String displayName,
            boolean active,
            String role,
            long fileCount,
            long folderCount,
            long sharedFileCount,
            long trashedCount,
            long usedBytes
    ) {
    }

    @Builder
    public record StorageAnalyticsResponse(
            long providerCapacityBytes,
            long providerUsedBytes,
            long providerRemainingBytes,
            double providerUsagePercent,
            int issueCount,
            List<String> issues,
            List<AdminUserResponse> users
    ) {
    }

    @Builder
    public record StorageCapacityUpdateRequest(
            Long providerCapacityBytes
    ) {
    }

    @Builder
    public record ProfileSettingsUpdateRequest(
            String displayName,
            String localeCode,
            String regionCode,
            Boolean marketingOptIn,
            Boolean privateProfile,
            Boolean emailNotification,
            Boolean securityNotification
    ) {
    }

    @Builder
    public record ProfileSettingsResponse(
            Long userId,
            String loginId,
            String displayName,
            String role,
            Boolean active,
            String localeCode,
            String regionCode,
            Boolean marketingOptIn,
            Boolean privateProfile,
            Boolean emailNotification,
            Boolean securityNotification,
            String profileImageUrl,
            Long driveUsedBytes,
            Long driveFileCount,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
