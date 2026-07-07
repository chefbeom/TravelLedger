package com.playdata.calen.drive.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.common.media.ImageThumbnailService;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveItemType;
import com.playdata.calen.drive.domain.DriveItemVersion;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveDownloadLinkRepository;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.drive.repository.DriveItemVersionRepository;
import com.playdata.calen.drive.repository.DriveShareRepository;
import com.playdata.calen.travel.service.TravelDriveLinkService;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class DriveService {

    private static final int DEFAULT_PHOTO_FILE_LIMIT = 1200;
    private static final int MAX_PHOTO_FILE_LIMIT = 5000;

    private final DriveItemRepository driveItemRepository;
    private final DriveItemVersionRepository driveItemVersionRepository;
    private final DriveShareRepository driveShareRepository;
    private final DriveDownloadLinkRepository driveDownloadLinkRepository;
    private final AppUserRepository appUserRepository;
    private final DriveStorageService driveStorageService;
    private final TravelMediaStorageService travelMediaStorageService;
    private final ImageThumbnailService imageThumbnailService;

    public DriveService(
            DriveItemRepository driveItemRepository,
            DriveItemVersionRepository driveItemVersionRepository,
            DriveShareRepository driveShareRepository,
            DriveDownloadLinkRepository driveDownloadLinkRepository,
            AppUserRepository appUserRepository,
            DriveStorageService driveStorageService,
            TravelMediaStorageService travelMediaStorageService,
            ImageThumbnailService imageThumbnailService
    ) {
        this.driveItemRepository = driveItemRepository;
        this.driveItemVersionRepository = driveItemVersionRepository;
        this.driveShareRepository = driveShareRepository;
        this.driveDownloadLinkRepository = driveDownloadLinkRepository;
        this.appUserRepository = appUserRepository;
        this.driveStorageService = driveStorageService;
        this.travelMediaStorageService = travelMediaStorageService;
        this.imageThumbnailService = imageThumbnailService;
    }

    @Transactional
    public DriveDtos.UploadCompleteResponse completeUpload(Long userId, DriveDtos.UploadCompleteRequest request) {
        AppUser owner = getOwner(userId);
        ensureUploadObjectKeyOwnedBy(owner.getId(), request);
        DriveDtos.UploadCompleteResponse completed = driveStorageService.completeUpload(request);
        return driveItemRepository.findByOwner_IdAndStoragePath(owner.getId(), completed.finalObjectKey())
                .map(this::toCompleteResponse)
                .orElseGet(() -> saveUploadedItem(owner, request, completed));
    }

    private void ensureUploadObjectKeyOwnedBy(Long ownerId, DriveDtos.UploadCompleteRequest request) {
        if (request == null || !StringUtils.hasText(request.finalObjectKey())) {
            return;
        }
        String expectedPrefix = "drive/" + ownerId + "/";
        if (!request.finalObjectKey().startsWith(expectedPrefix)) {
            throw new BadRequestException("Upload object key is outside the current user drive scope.");
        }
    }

    public List<DriveDtos.FileItemResponse> list(Long userId) {
        return driveItemRepository.findAllByOwner_IdOrderByLastModifiedAtDesc(getOwner(userId).getId()).stream()
                .filter(item -> !item.isTrashed())
                .filter(item -> item.getParent() == null)
                .sorted(resolveComparator("recent"))
                .map(this::toItemResponse)
                .toList();
    }

    public List<DriveDtos.FolderDestinationResponse> listFolderDestinations(Long userId) {
        AppUser owner = getOwner(userId);
        return driveItemRepository.findAllByOwner_Id(owner.getId()).stream()
                .filter(DriveItem::isFolder)
                .filter(item -> !item.isTrashed())
                .sorted(Comparator.comparing(this::buildFolderPath, String.CASE_INSENSITIVE_ORDER))
                .map(folder -> DriveDtos.FolderDestinationResponse.builder()
                        .id(folder.getId())
                        .fileOriginName(folder.getOriginalName())
                        .parentId(folder.getParent() != null ? folder.getParent().getId() : null)
                        .path(buildFolderPath(folder))
                        .lockedFile(folder.isLockedFile())
                        .systemManaged(folder.isSystemManaged())
                        .sourceType(folder.getSourceType())
                        .sourceReference(folder.getSourceReference())
                        .build())
                .toList();
    }

    public DriveDtos.FileListPageResponse listPage(Long userId, DriveDtos.ListPageRequest request) {
        AppUser owner = getOwner(userId);
        List<DriveItem> allItems = driveItemRepository.findAllByOwner_Id(owner.getId());
        Long parentId = request != null ? request.parentId() : null;
        int page = Math.max(0, request != null && request.page() != null ? request.page() : 0);
        int size = Math.max(1, request != null && request.size() != null ? request.size() : 30);
        String searchQuery = request != null ? request.searchQuery() : null;
        String extensionFilter = request != null ? request.extensionFilter() : null;
        String statusFilter = request != null ? request.statusFilter() : null;
        String sortOption = request != null ? request.sortOption() : null;
        boolean hasSearchQuery = StringUtils.hasText(searchQuery);

        List<DriveItem> filteredItems = allItems.stream()
                .filter(item -> hasSearchQuery || matchParent(item, parentId))
                .filter(item -> matchSearch(item, searchQuery))
                .filter(item -> matchExtension(item, extensionFilter))
                .filter(item -> matchStatus(item, statusFilter))
                .sorted(resolveComparator(sortOption))
                .toList();

        int fromIndex = Math.min(page * size, filteredItems.size());
        int toIndex = Math.min(fromIndex + size, filteredItems.size());
        List<DriveItem> pagedItems = filteredItems.subList(fromIndex, toIndex);

        return DriveDtos.FileListPageResponse.builder()
                .fileList(pagedItems.stream().map(this::toItemResponse).toList())
                .breadcrumbs(hasSearchQuery ? List.of() : buildBreadcrumbs(resolveParentFolder(owner.getId(), parentId)))
                .availableExtensions(allItems.stream()
                        .filter(DriveItem::isFile)
                        .map(DriveItem::getExtension)
                        .filter(StringUtils::hasText)
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .distinct()
                        .sorted()
                        .toList())
                .totalPage((int) Math.ceil((double) filteredItems.size() / size))
                .totalCount(filteredItems.size())
                .currentPage(page)
                .currentSize(size)
                .build();
    }

    public DriveDtos.HomeSummaryResponse getHomeSummary(Long userId) {
        AppUser owner = getOwner(userId);
        List<DriveItem> items = driveItemRepository.findAllByOwner_IdOrderByLastModifiedAtDesc(owner.getId());
        return DriveDtos.HomeSummaryResponse.builder()
                .driveItemCount(items.size())
                .fileCount(items.stream().filter(DriveItem::isFile).count())
                .folderCount(items.stream().filter(DriveItem::isFolder).count())
                .sharedCount(items.stream().filter(DriveItem::isSharedFile).count())
                .trashCount(items.stream().filter(DriveItem::isTrashed).count())
                .usedBytes(items.stream().filter(DriveItem::isFile).mapToLong(DriveItem::getFileSize).sum())
                .recentFiles(items.stream().limit(8).map(this::toItemResponse).toList())
                .build();
    }

    public List<DriveDtos.FileItemResponse> getRecentFiles(Long userId) {
        return driveItemRepository.findAllByOwner_IdOrderByLastModifiedAtDesc(getOwner(userId).getId()).stream()
                .filter(DriveItem::isFile)
                .filter(item -> !item.isTrashed())
                .limit(30)
                .map(this::toItemResponse)
                .toList();
    }

    public List<DriveDtos.FileItemResponse> getPhotoFiles(Long userId, Long parentId, String sortOption, Integer size) {
        AppUser owner = getOwner(userId);
        DriveItem parent = parentId == null ? null : resolveParentFolder(owner.getId(), parentId);
        int limit = Math.min(Math.max(size == null ? DEFAULT_PHOTO_FILE_LIMIT : size, 1), MAX_PHOTO_FILE_LIMIT);
        return driveItemRepository.findAllByOwner_Id(owner.getId()).stream()
                .filter(DriveItem::isFile)
                .filter(item -> !item.isTrashed())
                .filter(this::isImageItem)
                .filter(item -> parent == null || isInsideFolder(item, parent))
                .sorted(resolveComparator(sortOption))
                .limit(limit)
                .map(this::toItemResponse)
                .toList();
    }

    public List<DriveDtos.FileItemResponse> getTrashItems(Long userId) {
        return driveItemRepository.findAllByOwner_IdAndTrashedTrueOrderByDeletedAtDesc(getOwner(userId).getId()).stream()
                .map(this::toItemResponse)
                .toList();
    }

    @Transactional
    public DriveDtos.FileItemResponse createFolder(Long userId, DriveDtos.FolderRequest request) {
        AppUser owner = getOwner(userId);
        if (request == null || !StringUtils.hasText(request.folderName())) {
            throw new BadRequestException("폴더 이름을 입력해 주세요.");
        }

        DriveItem folder = new DriveItem();
        DriveItem parent = resolveParentFolder(owner.getId(), request.parentId());
        ensureUnlocked(parent);
        folder.setOwner(owner);
        folder.setParent(parent);
        folder.setItemType(DriveItemType.FOLDER);
        folder.setOriginalName(request.folderName().trim());
        folder.setExtension("");
        folder.setStoredName(request.folderName().trim());
        folder.setStoragePath(null);
        folder.setFileSize(0L);
        return toItemResponse(driveItemRepository.save(folder));
    }

    @Transactional
    public DriveDtos.ActionResponse moveToTrash(Long userId, Long fileId) {
        DriveItem item = getOwnedItem(userId, fileId);
        ensureUnlockedTree(item);
        markRecursivelyTrashed(item);
        return DriveDtos.ActionResponse.builder().action("trash").affectedCount(1).build();
    }

    @Transactional
    public DriveDtos.ActionResponse restoreFromTrash(Long userId, Long fileId) {
        restoreRecursively(getOwnedItem(userId, fileId));
        return DriveDtos.ActionResponse.builder().action("restore").affectedCount(1).build();
    }

    @Transactional
    public DriveDtos.ActionResponse deletePermanently(Long userId, Long fileId) {
        DriveItem item = getOwnedItem(userId, fileId);
        ensureUnlockedTree(item);
        List<DriveItem> descendants = collectDescendants(item);
        deleteDownloadLinks(descendants);
        deleteFileVersions(descendants);
        descendants.stream().filter(DriveItem::isFile).map(DriveItem::getStoragePath).forEach(driveStorageService::deleteObject);
        driveItemRepository.deleteAll(descendants);
        return DriveDtos.ActionResponse.builder().action("delete").affectedCount(descendants.size()).build();
    }

    @Transactional
    public DriveDtos.ActionResponse clearTrash(Long userId) {
        List<DriveItem> trashItems = driveItemRepository.findAllByOwner_IdAndTrashedTrueOrderByDeletedAtDesc(getOwner(userId).getId());
        List<DriveItem> targets = new ArrayList<>();
        for (DriveItem item : trashItems) {
            ensureUnlockedTree(item);
            if (targets.stream().noneMatch(existing -> Objects.equals(existing.getId(), item.getId()))) {
                targets.addAll(collectDescendants(item));
            }
        }
        deleteDownloadLinks(targets);
        deleteFileVersions(targets);
        targets.stream().filter(DriveItem::isFile).map(DriveItem::getStoragePath).forEach(driveStorageService::deleteObject);
        driveItemRepository.deleteAll(targets);
        return DriveDtos.ActionResponse.builder().action("clear-trash").affectedCount(targets.size()).build();
    }

    @Transactional
    public DriveDtos.ActionResponse moveToFolder(Long userId, Long fileId, Long targetParentId) {
        DriveItem item = getOwnedItem(userId, fileId);
        ensureUnlockedTree(item);
        DriveItem targetParent = resolveParentFolder(userId, targetParentId);
        ensureUnlocked(targetParent);
        ensureNotDescendant(item, targetParent);
        item.setParent(targetParent);
        return DriveDtos.ActionResponse.builder().action("move").affectedCount(1).build();
    }

    @Transactional
    public DriveDtos.FileItemResponse renameItem(Long userId, Long fileId, String nextName) {
        DriveItem item = getOwnedItem(userId, fileId);
        ensureUnlocked(item);
        if (!StringUtils.hasText(nextName)) {
            throw new BadRequestException("이름을 입력해 주세요.");
        }
        item.setOriginalName(nextName.trim());
        return toItemResponse(item);
    }

    @Transactional
    public DriveDtos.FileItemResponse setItemLocked(Long userId, Long fileId, boolean locked) {
        DriveItem item = getOwnedItem(userId, fileId);
        if (item.isTrashed()) {
            throw new BadRequestException("Items in trash cannot be locked or unlocked.");
        }
        if (isSystemManagedPath(item)) {
            throw new BadRequestException("Travel-linked drive items are read-only.");
        }
        item.setLockedFile(locked);
        return toItemResponse(item);
    }

    @Transactional
    public DriveDtos.ActionResponse moveItemsToFolder(Long userId, List<Long> fileIds, Long targetParentId) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new BadRequestException("이동할 파일을 선택해 주세요.");
        }
        int affected = 0;
        for (Long fileId : fileIds.stream().filter(Objects::nonNull).distinct().toList()) {
            moveToFolder(userId, fileId, targetParentId);
            affected += 1;
        }
        return DriveDtos.ActionResponse.builder().action("move").affectedCount(affected).build();
    }

    @Transactional
    public DriveDtos.ActionResponse restoreItemsFromTrash(Long userId, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new BadRequestException("복구할 파일을 선택해 주세요.");
        }
        int affected = 0;
        for (Long fileId : fileIds.stream().filter(Objects::nonNull).distinct().toList()) {
            restoreFromTrash(userId, fileId);
            affected += 1;
        }
        return DriveDtos.ActionResponse.builder().action("restore").affectedCount(affected).build();
    }

    public DriveFilePayload downloadFile(Long userId, Long fileId) {
        DriveItem item = getOwnedFile(userId, fileId);
        byte[] bytes = loadFileBytes(item);
        item.setLastAccessedAt(LocalDateTime.now());
        return new DriveFilePayload(bytes, resolveContentType(item.getExtension()), item.getOriginalName(), item.getFileSize());
    }

    @Transactional
    public DriveFilePayload downloadItemsAsZip(Long userId, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new BadRequestException("Select at least one drive item to download.");
        }

        AppUser owner = getOwner(userId);
        List<DriveItem> roots = fileIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(fileId -> driveItemRepository.findByIdAndOwner_Id(fileId, owner.getId())
                        .orElseThrow(() -> new NotFoundException("Drive item not found.")))
                .toList();

        List<ZipSource> zipSources = new ArrayList<>();
        Set<Long> includedFileIds = new HashSet<>();
        for (DriveItem root : roots) {
            if (root.isTrashed()) {
                throw new BadRequestException("Items in trash cannot be downloaded as a selection.");
            }
            collectZipSources(root, root, includedFileIds, zipSources);
        }
        if (zipSources.isEmpty()) {
            throw new BadRequestException("The selected folders do not contain downloadable files.");
        }

        byte[] zipBytes = buildZipBytes(zipSources);
        return new DriveFilePayload(
                zipBytes,
                "application/zip",
                buildZipFileName(roots),
                zipBytes.length
        );
    }

    @Transactional
    public String getDownloadUrl(Long userId, Long fileId) {
        DriveItem item = getOwnedFile(userId, fileId);
        item.setLastAccessedAt(LocalDateTime.now());
        if (isTravelLinkedFile(item)) {
            return "/api/file/" + item.getId() + "/download";
        }
        return driveStorageService.generateDownloadUrl(
                item.getStoragePath(),
                item.getOriginalName(),
                resolveContentType(item.getExtension())
        );
    }

    public ThumbnailPayload loadOwnedThumbnail(Long userId, Long fileId, Integer width) {
        return buildThumbnailPayload(getOwnedFile(userId, fileId), width);
    }

    public List<DriveDtos.FileVersionResponse> listFileVersions(Long userId, Long fileId) {
        DriveItem item = getOwnedFile(userId, fileId);
        return driveItemVersionRepository.findAllByItem_IdAndOwner_IdOrderByVersionNumberDescIdDesc(item.getId(), item.getOwner().getId()).stream()
                .map(this::toVersionResponse)
                .toList();
    }

    @Transactional
    public DriveDtos.FileItemResponse restoreFileVersion(Long userId, Long fileId, Long versionId) {
        DriveItem item = getOwnedFile(userId, fileId);
        ensureUnlocked(item);
        DriveItemVersion version = driveItemVersionRepository.findByIdAndItem_IdAndOwner_Id(
                        versionId,
                        item.getId(),
                        item.getOwner().getId()
                )
                .orElseThrow(() -> new NotFoundException("Drive file version not found."));

        item.setOriginalName(version.getOriginalName());
        item.setExtension(version.getExtension());
        item.setStoredName(version.getStoredName());
        item.setStoragePath(version.getStoragePath());
        item.setFileSize(version.getFileSize());
        recordFileVersion(item, "RESTORE");
        return toItemResponse(item);
    }
    public DriveItem getOwnedItem(Long userId, Long fileId) {
        return driveItemRepository.findByIdAndOwner_Id(fileId, getOwner(userId).getId())
                .orElseThrow(() -> new NotFoundException("드라이브 항목을 찾지 못했습니다."));
    }

    public DriveItem getOwnedFile(Long userId, Long fileId) {
        DriveItem item = getOwnedItem(userId, fileId);
        if (!item.isFile()) {
            throw new BadRequestException("파일만 사용할 수 있습니다.");
        }
        return item;
    }

    public AppUser getOwner(Long userId) {
        return appUserRepository.findById(userId)
                .filter(AppUser::isActive)
                .orElseThrow(() -> new NotFoundException("사용자 정보를 찾지 못했습니다."));
    }

    public DriveDtos.FileItemResponse toItemResponse(DriveItem item) {
        return DriveDtos.FileItemResponse.builder()
                .id(item.getId())
                .fileOriginName(item.getOriginalName())
                .fileSaveName(item.getStoredName())
                .fileSavePath(item.getStoragePath())
                .fileFormat(item.getExtension())
                .fileSize(item.getFileSize())
                .nodeType(item.getItemType().name())
                .parentId(item.getParent() != null ? item.getParent().getId() : null)
                .lockedFile(item.isLockedFile())
                .systemManaged(item.isSystemManaged())
                .sourceType(item.getSourceType())
                .sourceReference(item.getSourceReference())
                .sharedFile(item.isSharedFile())
                .trashed(item.isTrashed())
                .deletedAt(item.getDeletedAt())
                .uploadDate(item.getUploadedAt())
                .lastModifyDate(item.getLastModifiedAt())
                .downloadUrl(item.isFile() && StringUtils.hasText(item.getStoragePath()) ? "/api/file/" + item.getId() + "/download" : null)
                .thumbnailUrl(item.isFile() ? "/api/file/" + item.getId() + "/thumbnail" : null)
                .presignedUrlExpiresIn(6000)
                .ownerLoginId(item.getOwner().getLoginId())
                .ownerDisplayName(item.getOwner().getDisplayName())
                .folderPath(buildParentFolderPath(item))
                .shareCount(driveShareRepository.countByItem_Id(item.getId()))
                .build();
    }

    public record DriveFilePayload(byte[] bytes, String contentType, String fileName, long contentLength) {}

    private DriveDtos.FileVersionResponse toVersionResponse(DriveItemVersion version) {
        return DriveDtos.FileVersionResponse.builder()
                .id(version.getId())
                .fileId(version.getItem().getId())
                .versionNumber(version.getVersionNumber())
                .fileOriginName(version.getOriginalName())
                .fileFormat(version.getExtension())
                .fileSize(version.getFileSize())
                .contentType(version.getContentType())
                .source(version.getSource())
                .createdAt(version.getCreatedAt())
                .build();
    }

    public record ThumbnailPayload(byte[] bytes, String contentType, String eTag, long lastModifiedEpochMillis) {}

    private record ZipSource(DriveItem item, String entryName) {}

    private DriveDtos.UploadCompleteResponse saveUploadedItem(AppUser owner, DriveDtos.UploadCompleteRequest request, DriveDtos.UploadCompleteResponse completed) {
        DriveItem item = new DriveItem();
        item.setOwner(owner);
        item.setParent(resolveUploadParentFolder(owner, request));
        item.setItemType(DriveItemType.FILE);
        item.setOriginalName(completed.fileOriginName());
        item.setExtension(completed.fileFormat());
        item.setStoredName(completed.fileSaveName());
        item.setStoragePath(completed.finalObjectKey());
        item.setFileSize(driveStorageService.resolveObjectSize(completed.finalObjectKey()));
        DriveItem savedItem = driveItemRepository.save(item);
        recordFileVersion(savedItem, "UPLOAD");
        return toCompleteResponse(savedItem);
    }

    private void recordFileVersion(DriveItem item, String source) {
        if (item == null || !item.isFile()) {
            return;
        }
        long existingVersions = driveItemVersionRepository.countByItem_IdAndOwner_Id(item.getId(), item.getOwner().getId());
        DriveItemVersion version = new DriveItemVersion();
        version.setItem(item);
        version.setOwner(item.getOwner());
        version.setVersionNumber((int) existingVersions + 1);
        version.setOriginalName(item.getOriginalName());
        version.setExtension(item.getExtension());
        version.setStoredName(item.getStoredName());
        version.setStoragePath(item.getStoragePath());
        version.setContentType(resolveContentType(item.getExtension()));
        version.setFileSize(item.getFileSize());
        version.setSource(source);
        version.setCreatedAt(LocalDateTime.now());
        driveItemVersionRepository.save(version);
    }

    private DriveItem resolveUploadParentFolder(AppUser owner, DriveDtos.UploadCompleteRequest request) {
        DriveItem parent = resolveParentFolder(owner.getId(), request.parentId());
        ensureUnlocked(parent);
        for (String folderName : extractRelativeFolderSegments(request.relativePath())) {
            parent = findOrCreateUploadFolder(owner, parent, folderName);
            ensureUnlocked(parent);
        }
        return parent;
    }

    private List<String> extractRelativeFolderSegments(String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            return List.of();
        }

        String normalizedPath = relativePath.replace('\\', '/').trim();
        int lastSeparatorIndex = normalizedPath.lastIndexOf('/');
        if (lastSeparatorIndex <= 0) {
            return List.of();
        }

        List<String> segments = new ArrayList<>();
        for (String rawSegment : normalizedPath.substring(0, lastSeparatorIndex).split("/")) {
            String segment = rawSegment.trim();
            if (!StringUtils.hasText(segment) || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment) || segment.length() > 255) {
                throw new BadRequestException("Invalid folder name in upload path.");
            }
            segments.add(segment);
        }
        return segments;
    }

    private DriveItem findOrCreateUploadFolder(AppUser owner, DriveItem parent, String folderName) {
        ensureUnlocked(parent);
        Long parentId = parent != null ? parent.getId() : null;
        return driveItemRepository.findAllByOwner_Id(owner.getId()).stream()
                .filter(DriveItem::isFolder)
                .filter(item -> !item.isTrashed())
                .filter(item -> Objects.equals(item.getParent() != null ? item.getParent().getId() : null, parentId))
                .filter(item -> item.getOriginalName().equalsIgnoreCase(folderName))
                .findFirst()
                .orElseGet(() -> createUploadFolder(owner, parent, folderName));
    }

    private DriveItem createUploadFolder(AppUser owner, DriveItem parent, String folderName) {
        DriveItem folder = new DriveItem();
        folder.setOwner(owner);
        folder.setParent(parent);
        folder.setItemType(DriveItemType.FOLDER);
        folder.setOriginalName(folderName);
        folder.setExtension("");
        folder.setStoredName(folderName);
        folder.setStoragePath(null);
        folder.setFileSize(0L);
        return driveItemRepository.save(folder);
    }

    private DriveDtos.UploadCompleteResponse toCompleteResponse(DriveItem item) {
        return DriveDtos.UploadCompleteResponse.builder()
                .fileOriginName(item.getOriginalName())
                .fileSaveName(item.getStoredName())
                .fileFormat(item.getExtension())
                .finalObjectKey(item.getStoragePath())
                .build();
    }

    private boolean isImageItem(DriveItem item) {
        return item != null && item.isFile() && resolveContentType(item.getExtension()).startsWith("image/");
    }

    private boolean isInsideFolder(DriveItem item, DriveItem folder) {
        if (item == null || folder == null) {
            return false;
        }
        DriveItem cursor = item.getParent();
        while (cursor != null) {
            if (Objects.equals(cursor.getId(), folder.getId())) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }
    private boolean matchParent(DriveItem item, Long parentId) {
        Long itemParentId = item.getParent() != null ? item.getParent().getId() : null;
        return Objects.equals(itemParentId, parentId);
    }

    private boolean matchSearch(DriveItem item, String searchQuery) {
        if (!StringUtils.hasText(searchQuery)) {
            return true;
        }
        return item.getOriginalName().toLowerCase(Locale.ROOT).contains(searchQuery.trim().toLowerCase(Locale.ROOT));
    }

    private boolean matchExtension(DriveItem item, String extensionFilter) {
        if (!StringUtils.hasText(extensionFilter) || item.isFolder()) {
            return true;
        }
        return extensionFilter.trim().equalsIgnoreCase(item.getExtension());
    }

    private boolean matchStatus(DriveItem item, String statusFilter) {
        if (!StringUtils.hasText(statusFilter)) {
            return !item.isTrashed();
        }
        return switch (statusFilter.trim().toLowerCase(Locale.ROOT)) {
            case "trash" -> item.isTrashed();
            case "shared" -> item.isSharedFile() && !item.isTrashed();
            case "all" -> true;
            default -> !item.isTrashed();
        };
    }

    private Comparator<DriveItem> resolveComparator(String sortOption) {
        Comparator<DriveItem> folderFirst = Comparator.comparingInt(item -> item.isFolder() ? 0 : 1);
        Comparator<DriveItem> nameAsc = Comparator.comparing(
                item -> item.getOriginalName() == null ? "" : item.getOriginalName(),
                String.CASE_INSENSITIVE_ORDER
        );
        if (!StringUtils.hasText(sortOption)) {
            return folderFirst.thenComparing(nameAsc);
        }
        return switch (sortOption.trim().toLowerCase(Locale.ROOT)) {
            case "recent" -> folderFirst
                    .thenComparing(Comparator.comparing(DriveItem::getLastModifiedAt).reversed())
                    .thenComparing(nameAsc);
            case "oldest" -> folderFirst
                    .thenComparing(DriveItem::getUploadedAt)
                    .thenComparing(nameAsc);
            case "size" -> folderFirst
                    .thenComparing(Comparator.comparingLong(DriveItem::getFileSize).reversed())
                    .thenComparing(nameAsc);
            default -> folderFirst.thenComparing(nameAsc);
        };
    }

    private List<DriveDtos.FileBreadcrumbResponse> buildBreadcrumbs(DriveItem folder) {
        List<DriveDtos.FileBreadcrumbResponse> breadcrumbs = new ArrayList<>();
        DriveItem cursor = folder;
        while (cursor != null) {
            breadcrumbs.add(0, DriveDtos.FileBreadcrumbResponse.builder()
                    .id(cursor.getId())
                    .fileOriginName(cursor.getOriginalName())
                    .parentId(cursor.getParent() != null ? cursor.getParent().getId() : null)
                    .build());
            cursor = cursor.getParent();
        }
        return breadcrumbs;
    }

    private String buildFolderPath(DriveItem folder) {
        List<String> names = new ArrayList<>();
        DriveItem cursor = folder;
        while (cursor != null) {
            names.add(0, cursor.getOriginalName());
            cursor = cursor.getParent();
        }
        return String.join(" / ", names);
    }

    private String buildParentFolderPath(DriveItem item) {
        if (item == null || item.getParent() == null) {
            return "내 드라이브";
        }
        return "내 드라이브 / " + buildFolderPath(item.getParent());
    }

    private DriveItem resolveParentFolder(Long ownerId, Long parentId) {
        if (parentId == null) {
            return null;
        }
        DriveItem parent = getOwnedItem(ownerId, parentId);
        if (!parent.isFolder()) {
            throw new BadRequestException("폴더만 상위 위치로 선택할 수 있습니다.");
        }
        return parent;
    }

    private void ensureNotDescendant(DriveItem item, DriveItem targetParent) {
        DriveItem cursor = targetParent;
        while (cursor != null) {
            if (Objects.equals(cursor.getId(), item.getId())) {
                throw new BadRequestException("자기 자신 또는 하위 폴더로 이동할 수 없습니다.");
            }
            cursor = cursor.getParent();
        }
    }

    public void ensureUnlocked(DriveItem item) {
        DriveItem cursor = item;
        while (cursor != null) {
            if (cursor.isSystemManaged()) {
                throw new BadRequestException("Travel-linked drive items are read-only.");
            }
            if (cursor.isLockedFile()) {
                throw new BadRequestException("Locked drive items cannot be changed. Unlock the item first.");
            }
            cursor = cursor.getParent();
        }
    }

    private boolean isSystemManagedPath(DriveItem item) {
        DriveItem cursor = item;
        while (cursor != null) {
            if (cursor.isSystemManaged()) {
                return true;
            }
            cursor = cursor.getParent();
        }
        return false;
    }

    public void ensureUnlockedTree(DriveItem item) {
        ensureUnlocked(item);
        for (DriveItem candidate : collectDescendants(item)) {
            ensureUnlocked(candidate);
        }
    }

    private void markRecursivelyTrashed(DriveItem item) {
        item.markTrashed();
        childrenOf(item).forEach(this::markRecursivelyTrashed);
    }

    private void restoreRecursively(DriveItem item) {
        item.restore();
        childrenOf(item).forEach(this::restoreRecursively);
    }

    private List<DriveItem> collectDescendants(DriveItem item) {
        List<DriveItem> items = new ArrayList<>();
        items.add(item);
        for (DriveItem child : childrenOf(item)) {
            items.addAll(collectDescendants(child));
        }
        return items;
    }

    private void collectZipSources(
            DriveItem root,
            DriveItem current,
            Set<Long> includedFileIds,
            List<ZipSource> zipSources
    ) {
        if (current.isTrashed()) {
            return;
        }
        if (current.isFile() && includedFileIds.add(current.getId())) {
            zipSources.add(new ZipSource(current, buildZipEntryName(root, current, zipSources)));
            return;
        }
        for (DriveItem child : childrenOf(current)) {
            collectZipSources(root, child, includedFileIds, zipSources);
        }
    }

    private byte[] buildZipBytes(List<ZipSource> zipSources) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            for (ZipSource source : zipSources) {
                ZipEntry entry = new ZipEntry(source.entryName());
                zip.putNextEntry(entry);
                zip.write(loadFileBytes(source.item()));
                zip.closeEntry();
                source.item().setLastAccessedAt(LocalDateTime.now());
            }
        } catch (IOException exception) {
            throw new BadRequestException("Failed to create a download archive.");
        }
        return buffer.toByteArray();
    }

    private String buildZipEntryName(DriveItem root, DriveItem file, List<ZipSource> existingSources) {
        List<String> segments = new ArrayList<>();
        DriveItem cursor = file;
        while (cursor != null) {
            segments.add(0, sanitizeZipSegment(cursor.getOriginalName(), cursor.getId()));
            if (Objects.equals(cursor.getId(), root.getId())) {
                break;
            }
            cursor = cursor.getParent();
        }
        String candidate = String.join("/", segments);
        Set<String> usedNames = existingSources.stream()
                .map(ZipSource::entryName)
                .collect(java.util.stream.Collectors.toSet());
        return uniqueZipEntryName(candidate, usedNames);
    }

    private String uniqueZipEntryName(String candidate, Set<String> usedNames) {
        String normalized = StringUtils.hasText(candidate) ? candidate : "download";
        if (usedNames.add(normalized)) {
            return normalized;
        }

        int slashIndex = normalized.lastIndexOf('/');
        int dotIndex = normalized.lastIndexOf('.');
        boolean hasExtension = dotIndex > slashIndex;
        String prefix = hasExtension ? normalized.substring(0, dotIndex) : normalized;
        String suffix = hasExtension ? normalized.substring(dotIndex) : "";
        int copyIndex = 2;
        String nextName;
        do {
            nextName = prefix + " (" + copyIndex + ")" + suffix;
            copyIndex += 1;
        } while (!usedNames.add(nextName));
        return nextName;
    }

    private String buildZipFileName(List<DriveItem> roots) {
        if (roots.size() == 1) {
            return sanitizeZipSegment(roots.get(0).getOriginalName(), roots.get(0).getId()) + ".zip";
        }
        return "calendrive-selection.zip";
    }

    private String sanitizeZipSegment(String value, Long fallbackId) {
        String sanitized = value == null ? "" : value
                .replace('\\', '_')
                .replace('/', '_')
                .replaceAll("\\p{Cntrl}+", "_")
                .trim();
        if (!StringUtils.hasText(sanitized) || ".".equals(sanitized) || "..".equals(sanitized)) {
            return "item-" + (fallbackId != null ? fallbackId : "download");
        }
        return sanitized;
    }

    private List<DriveItem> childrenOf(DriveItem item) {
        return driveItemRepository.findAllByOwner_Id(item.getOwner().getId()).stream()
                .filter(candidate -> candidate.getParent() != null)
                .filter(candidate -> Objects.equals(candidate.getParent().getId(), item.getId()))
                .toList();
    }

    private void deleteFileVersions(List<DriveItem> items) {
        List<Long> itemIds = items.stream()
                .filter(DriveItem::isFile)
                .map(DriveItem::getId)
                .filter(Objects::nonNull)
                .toList();
        if (!itemIds.isEmpty()) {
            driveItemVersionRepository.deleteAllByItem_IdIn(itemIds);
        }
    }

    private void deleteDownloadLinks(List<DriveItem> items) {
        List<Long> itemIds = items.stream()
                .map(DriveItem::getId)
                .filter(Objects::nonNull)
                .toList();
        if (!itemIds.isEmpty()) {
            driveDownloadLinkRepository.deleteAllByItem_IdIn(itemIds);
        }
    }

    private ThumbnailPayload buildThumbnailPayload(DriveItem item, Integer width) {
        String contentType = resolveContentType(item.getExtension());
        if (!contentType.startsWith("image/")) {
            return null;
        }

        ThumbnailPayload travelThumbnail = buildTravelLinkedThumbnailPayload(item, contentType, width);
        if (travelThumbnail != null) {
            return travelThumbnail;
        }

        byte[] sourceBytes = loadFileBytes(item);
        return imageThumbnailService.createPreparedThumbnails(sourceBytes, contentType, List.of(width == null ? 320 : width)).stream()
                .findFirst()
                .map(thumbnail -> new ThumbnailPayload(
                        thumbnail.bytes(),
                        thumbnail.contentType(),
                        "\"" + item.getId() + "-" + item.getLastModifiedAt() + "-" + thumbnail.width() + "\"",
                        item.getLastModifiedAt() != null ? item.getLastModifiedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : System.currentTimeMillis()
                ))
                .orElseGet(() -> new ThumbnailPayload(
                        sourceBytes,
                        contentType,
                        "\"" + item.getId() + "-" + item.getLastModifiedAt() + "-full\"",
                        item.getLastModifiedAt() != null ? item.getLastModifiedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : System.currentTimeMillis()
                ));
    }
    private ThumbnailPayload buildTravelLinkedThumbnailPayload(DriveItem item, String contentType, Integer width) {
        if (!isTravelLinkedFile(item)) {
            return null;
        }
        TravelMediaStorageService.PreparedThumbnail preparedThumbnail = travelMediaStorageService.loadThumbnail(
                item.getStoragePath(),
                contentType,
                width
        );
        if (preparedThumbnail == null) {
            return null;
        }
        try (var inputStream = preparedThumbnail.resource().getInputStream()) {
            byte[] bytes = inputStream.readAllBytes();
            return new ThumbnailPayload(
                    bytes,
                    preparedThumbnail.contentType(),
                    "\"" + item.getId() + "-" + item.getLastModifiedAt() + "-travel-thumb\"",
                    item.getLastModifiedAt() != null
                            ? item.getLastModifiedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                            : System.currentTimeMillis()
            );
        } catch (IOException exception) {
            return null;
        }
    }

    byte[] loadFileBytes(DriveItem item) {
        if (isTravelLinkedFile(item)) {
            try (var inputStream = travelMediaStorageService.loadAsResource(item.getStoragePath()).getInputStream()) {
                return inputStream.readAllBytes();
            } catch (IOException exception) {
                throw new BadRequestException("Travel-linked file could not be loaded.");
            }
        }
        return driveStorageService.loadObjectBytes(item.getStoragePath());
    }

    private boolean isTravelLinkedFile(DriveItem item) {
        if (item == null || !item.isFile()) {
            return false;
        }
        return TravelDriveLinkService.SOURCE_TRAVEL_MEDIA.equals(item.getSourceType())
                || TravelDriveLinkService.SOURCE_TRAVEL_GPX.equals(item.getSourceType());
    }

    public String resolveContentType(String extension) {
        String normalized = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG_VALUE;
            case "png" -> MediaType.IMAGE_PNG_VALUE;
            case "gif" -> MediaType.IMAGE_GIF_VALUE;
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "pdf" -> MediaType.APPLICATION_PDF_VALUE;
            case "gpx", "xml" -> "application/gpx+xml";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }
}
