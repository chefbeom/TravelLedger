package com.playdata.calen.drive.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.common.media.ImageThumbnailService;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveItemType;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.drive.repository.DriveShareRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class DriveService {

    private final DriveItemRepository driveItemRepository;
    private final DriveShareRepository driveShareRepository;
    private final AppUserRepository appUserRepository;
    private final DriveStorageService driveStorageService;
    private final ImageThumbnailService imageThumbnailService;

    public DriveService(
            DriveItemRepository driveItemRepository,
            DriveShareRepository driveShareRepository,
            AppUserRepository appUserRepository,
            DriveStorageService driveStorageService,
            ImageThumbnailService imageThumbnailService
    ) {
        this.driveItemRepository = driveItemRepository;
        this.driveShareRepository = driveShareRepository;
        this.appUserRepository = appUserRepository;
        this.driveStorageService = driveStorageService;
        this.imageThumbnailService = imageThumbnailService;
    }

    @Transactional
    public DriveDtos.UploadCompleteResponse completeUpload(Long userId, DriveDtos.UploadCompleteRequest request) {
        AppUser owner = getOwner(userId);
        DriveDtos.UploadCompleteResponse completed = driveStorageService.completeUpload(request);
        return driveItemRepository.findByOwner_IdAndStoragePath(owner.getId(), completed.finalObjectKey())
                .map(this::toCompleteResponse)
                .orElseGet(() -> saveUploadedItem(owner, request, completed));
    }

    public List<DriveDtos.FileItemResponse> list(Long userId) {
        return driveItemRepository.findAllByOwner_IdOrderByLastModifiedAtDesc(getOwner(userId).getId()).stream()
                .filter(item -> !item.isTrashed())
                .filter(item -> item.getParent() == null)
                .map(this::toItemResponse)
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

        List<DriveItem> filteredItems = allItems.stream()
                .filter(item -> matchParent(item, parentId))
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
                .breadcrumbs(buildBreadcrumbs(resolveParentFolder(owner.getId(), parentId)))
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
        folder.setOwner(owner);
        folder.setParent(resolveParentFolder(owner.getId(), request.parentId()));
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
        markRecursivelyTrashed(getOwnedItem(userId, fileId));
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
        List<DriveItem> descendants = collectDescendants(item);
        descendants.stream().filter(DriveItem::isFile).map(DriveItem::getStoragePath).forEach(driveStorageService::deleteObject);
        driveItemRepository.deleteAll(descendants);
        return DriveDtos.ActionResponse.builder().action("delete").affectedCount(descendants.size()).build();
    }

    @Transactional
    public DriveDtos.ActionResponse clearTrash(Long userId) {
        List<DriveItem> trashItems = driveItemRepository.findAllByOwner_IdAndTrashedTrueOrderByDeletedAtDesc(getOwner(userId).getId());
        List<DriveItem> targets = new ArrayList<>();
        for (DriveItem item : trashItems) {
            if (targets.stream().noneMatch(existing -> Objects.equals(existing.getId(), item.getId()))) {
                targets.addAll(collectDescendants(item));
            }
        }
        targets.stream().filter(DriveItem::isFile).map(DriveItem::getStoragePath).forEach(driveStorageService::deleteObject);
        driveItemRepository.deleteAll(targets);
        return DriveDtos.ActionResponse.builder().action("clear-trash").affectedCount(targets.size()).build();
    }

    @Transactional
    public DriveDtos.ActionResponse moveToFolder(Long userId, Long fileId, Long targetParentId) {
        DriveItem item = getOwnedItem(userId, fileId);
        DriveItem targetParent = resolveParentFolder(userId, targetParentId);
        ensureNotDescendant(item, targetParent);
        item.setParent(targetParent);
        return DriveDtos.ActionResponse.builder().action("move").affectedCount(1).build();
    }

    @Transactional
    public DriveDtos.FileItemResponse renameItem(Long userId, Long fileId, String nextName) {
        DriveItem item = getOwnedItem(userId, fileId);
        if (!StringUtils.hasText(nextName)) {
            throw new BadRequestException("이름을 입력해 주세요.");
        }
        item.setOriginalName(nextName.trim());
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
        byte[] bytes = driveStorageService.loadObjectBytes(item.getStoragePath());
        item.setLastAccessedAt(LocalDateTime.now());
        return new DriveFilePayload(bytes, resolveContentType(item.getExtension()), item.getOriginalName(), item.getFileSize());
    }

    public String getDownloadUrl(Long userId, Long fileId) {
        return driveStorageService.generateDownloadUrl(getOwnedFile(userId, fileId).getStoragePath());
    }

    public ThumbnailPayload loadOwnedThumbnail(Long userId, Long fileId, Integer width) {
        return buildThumbnailPayload(getOwnedFile(userId, fileId), width);
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
                .sharedFile(item.isSharedFile())
                .trashed(item.isTrashed())
                .deletedAt(item.getDeletedAt())
                .uploadDate(item.getUploadedAt())
                .lastModifyDate(item.getLastModifiedAt())
                .downloadUrl(item.isFile() && StringUtils.hasText(item.getStoragePath()) ? "/api/file/" + item.getId() + "/download-link" : null)
                .thumbnailUrl(item.isFile() ? "/api/file/" + item.getId() + "/thumbnail" : null)
                .presignedUrlExpiresIn(6000)
                .ownerLoginId(item.getOwner().getLoginId())
                .ownerDisplayName(item.getOwner().getDisplayName())
                .shareCount(driveShareRepository.countByItem_Id(item.getId()))
                .build();
    }

    public record DriveFilePayload(byte[] bytes, String contentType, String fileName, long contentLength) {}

    public record ThumbnailPayload(byte[] bytes, String contentType, String eTag, long lastModifiedEpochMillis) {}

    private DriveDtos.UploadCompleteResponse saveUploadedItem(AppUser owner, DriveDtos.UploadCompleteRequest request, DriveDtos.UploadCompleteResponse completed) {
        DriveItem item = new DriveItem();
        item.setOwner(owner);
        item.setParent(resolveParentFolder(owner.getId(), request.parentId()));
        item.setItemType(DriveItemType.FILE);
        item.setOriginalName(completed.fileOriginName());
        item.setExtension(completed.fileFormat());
        item.setStoredName(completed.fileSaveName());
        item.setStoragePath(completed.finalObjectKey());
        item.setFileSize(driveStorageService.resolveObjectSize(completed.finalObjectKey()));
        return toCompleteResponse(driveItemRepository.save(item));
    }

    private DriveDtos.UploadCompleteResponse toCompleteResponse(DriveItem item) {
        return DriveDtos.UploadCompleteResponse.builder()
                .fileOriginName(item.getOriginalName())
                .fileSaveName(item.getStoredName())
                .fileFormat(item.getExtension())
                .finalObjectKey(item.getStoragePath())
                .build();
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
        if (!StringUtils.hasText(sortOption)) {
            return Comparator.comparing(DriveItem::getItemType).thenComparing(DriveItem::getOriginalName, String.CASE_INSENSITIVE_ORDER);
        }
        return switch (sortOption.trim().toLowerCase(Locale.ROOT)) {
            case "recent" -> Comparator.comparing(DriveItem::getLastModifiedAt).reversed();
            case "oldest" -> Comparator.comparing(DriveItem::getUploadedAt);
            case "size" -> Comparator.comparingLong(DriveItem::getFileSize).reversed();
            default -> Comparator.comparing(DriveItem::getItemType).thenComparing(DriveItem::getOriginalName, String.CASE_INSENSITIVE_ORDER);
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

    private List<DriveItem> childrenOf(DriveItem item) {
        return driveItemRepository.findAllByOwner_Id(item.getOwner().getId()).stream()
                .filter(candidate -> candidate.getParent() != null)
                .filter(candidate -> Objects.equals(candidate.getParent().getId(), item.getId()))
                .toList();
    }

    private ThumbnailPayload buildThumbnailPayload(DriveItem item, Integer width) {
        String contentType = resolveContentType(item.getExtension());
        if (!contentType.startsWith("image/")) {
            return null;
        }

        byte[] sourceBytes = driveStorageService.loadObjectBytes(item.getStoragePath());
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

    private String resolveContentType(String extension) {
        String normalized = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG_VALUE;
            case "png" -> MediaType.IMAGE_PNG_VALUE;
            case "gif" -> MediaType.IMAGE_GIF_VALUE;
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "pdf" -> MediaType.APPLICATION_PDF_VALUE;
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }
}
