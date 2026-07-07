package com.playdata.calen.travel.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveItemType;
import com.playdata.calen.drive.repository.DriveDownloadLinkRepository;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.drive.repository.DriveItemVersionRepository;
import com.playdata.calen.travel.domain.TravelMediaAsset;
import com.playdata.calen.travel.domain.TravelMediaType;
import com.playdata.calen.travel.domain.TravelPlan;
import com.playdata.calen.travel.domain.TravelRouteSegment;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class TravelDriveLinkService {

    public static final String SOURCE_TRAVEL_ROOT = "TRAVEL_ROOT";
    public static final String SOURCE_TRAVEL_PLAN = "TRAVEL_PLAN";
    public static final String SOURCE_TRAVEL_SECTION = "TRAVEL_SECTION";
    public static final String SOURCE_TRAVEL_MEDIA = "TRAVEL_MEDIA";
    public static final String SOURCE_TRAVEL_GPX = "TRAVEL_GPX";

    private static final String ROOT_FOLDER_NAME = "\uC5EC\uD589";
    private static final String PHOTO_FOLDER_NAME = "\uC0AC\uC9C4";
    private static final String RECEIPT_FOLDER_NAME = "\uC601\uC218\uC99D";
    private static final String GPX_FOLDER_NAME = "GPX";

    private final DriveItemRepository driveItemRepository;
    private final DriveItemVersionRepository driveItemVersionRepository;
    private final DriveDownloadLinkRepository driveDownloadLinkRepository;

    @Transactional
    public void linkMediaAsset(TravelMediaAsset asset) {
        if (asset == null || asset.getId() == null || asset.getPlan() == null || asset.getPlan().getOwner() == null) {
            return;
        }
        if (!hasLinkableStoragePath(asset.getStoragePath())) {
            return;
        }

        TravelPlan plan = asset.getPlan();
        AppUser owner = plan.getOwner();
        DriveItem parent = resolveMediaSectionFolder(plan, asset.getMediaType());
        DriveItem item = driveItemRepository
                .findByOwner_IdAndSourceTypeAndSourceReference(owner.getId(), SOURCE_TRAVEL_MEDIA, String.valueOf(asset.getId()))
                .orElseGet(DriveItem::new);

        applyLinkedFile(
                item,
                owner,
                parent,
                asset.getOriginalFileName(),
                asset.getStoredFileName(),
                asset.getStoragePath(),
                asset.getFileSize() == null ? 0L : asset.getFileSize(),
                SOURCE_TRAVEL_MEDIA,
                String.valueOf(asset.getId())
        );
        driveItemRepository.save(item);
    }

    @Transactional
    public void removeMediaLink(TravelMediaAsset asset) {
        if (asset == null || asset.getId() == null || asset.getPlan() == null || asset.getPlan().getOwner() == null) {
            return;
        }
        removeSourceItems(asset.getPlan().getOwner().getId(), SOURCE_TRAVEL_MEDIA, String.valueOf(asset.getId()));
    }

    @Transactional
    public void removeMediaLinks(Collection<TravelMediaAsset> assets) {
        if (assets == null || assets.isEmpty()) {
            return;
        }
        assets.forEach(this::removeMediaLink);
    }

    @Transactional
    public void replaceRouteGpxLinks(TravelRouteSegment routeSegment, List<TravelLinkedFile> files) {
        if (routeSegment == null || routeSegment.getId() == null || routeSegment.getPlan() == null || routeSegment.getPlan().getOwner() == null) {
            return;
        }
        removeRouteGpxLinks(routeSegment);

        List<TravelLinkedFile> linkableFiles = files == null ? List.of() : files.stream()
                .filter(file -> file != null && hasLinkableStoragePath(file.storagePath()))
                .toList();
        if (linkableFiles.isEmpty()) {
            return;
        }

        TravelPlan plan = routeSegment.getPlan();
        AppUser owner = plan.getOwner();
        DriveItem parent = resolveSectionFolder(plan, GPX_FOLDER_NAME, "gpx");
        linkableFiles.forEach(file -> {
            DriveItem item = new DriveItem();
            applyLinkedFile(
                    item,
                    owner,
                    parent,
                    file.originalFileName(),
                    storedNameFromPath(file.storagePath()),
                    file.storagePath(),
                    file.fileSize(),
                    SOURCE_TRAVEL_GPX,
                    String.valueOf(routeSegment.getId())
            );
            driveItemRepository.save(item);
        });
    }

    @Transactional
    public void removeRouteGpxLinks(TravelRouteSegment routeSegment) {
        if (routeSegment == null || routeSegment.getId() == null || routeSegment.getPlan() == null || routeSegment.getPlan().getOwner() == null) {
            return;
        }
        removeSourceItems(routeSegment.getPlan().getOwner().getId(), SOURCE_TRAVEL_GPX, String.valueOf(routeSegment.getId()));
    }

    @Transactional
    public void removePlanLinks(TravelPlan plan) {
        if (plan == null || plan.getId() == null || plan.getOwner() == null) {
            return;
        }
        driveItemRepository.findByOwner_IdAndSourceTypeAndSourceReference(
                        plan.getOwner().getId(),
                        SOURCE_TRAVEL_PLAN,
                        String.valueOf(plan.getId())
                )
                .ifPresent(this::deleteMetadataTree);
    }

    @Transactional
    public void ensurePlanFolder(TravelPlan plan) {
        if (plan == null || plan.getId() == null || plan.getOwner() == null) {
            return;
        }
        resolvePlanFolder(plan);
    }

    private DriveItem resolveMediaSectionFolder(TravelPlan plan, TravelMediaType mediaType) {
        if (mediaType == TravelMediaType.RECEIPT) {
            return resolveSectionFolder(plan, RECEIPT_FOLDER_NAME, "receipts");
        }
        return resolveSectionFolder(plan, PHOTO_FOLDER_NAME, "photos");
    }

    private DriveItem resolveSectionFolder(TravelPlan plan, String folderName, String sectionKey) {
        DriveItem planFolder = resolvePlanFolder(plan);
        return findOrCreateFolder(
                plan.getOwner(),
                planFolder,
                folderName,
                SOURCE_TRAVEL_SECTION,
                plan.getId() + ":" + sectionKey
        );
    }

    private DriveItem resolvePlanFolder(TravelPlan plan) {
        DriveItem root = resolveRootFolder(plan.getOwner());
        return findOrCreateFolder(
                plan.getOwner(),
                root,
                buildPlanFolderName(plan),
                SOURCE_TRAVEL_PLAN,
                String.valueOf(plan.getId())
        );
    }

    private DriveItem resolveRootFolder(AppUser owner) {
        return findOrCreateFolder(owner, null, ROOT_FOLDER_NAME, SOURCE_TRAVEL_ROOT, "root");
    }

    private DriveItem findOrCreateFolder(AppUser owner, DriveItem parent, String folderName, String sourceType, String sourceReference) {
        DriveItem folder = driveItemRepository
                .findByOwner_IdAndSourceTypeAndSourceReference(owner.getId(), sourceType, sourceReference)
                .orElseGet(DriveItem::new);
        folder.setOwner(owner);
        folder.setParent(parent);
        folder.setItemType(DriveItemType.FOLDER);
        folder.setOriginalName(safeName(folderName, ROOT_FOLDER_NAME));
        folder.setStoredName(safeName(folderName, "travel"));
        folder.setExtension("");
        folder.setStoragePath(null);
        folder.setFileSize(0L);
        folder.setLockedFile(true);
        folder.setSystemManaged(true);
        folder.setSourceType(sourceType);
        folder.setSourceReference(sourceReference);
        folder.setTrashed(false);
        folder.setDeletedAt(null);
        return driveItemRepository.save(folder);
    }

    private void applyLinkedFile(
            DriveItem item,
            AppUser owner,
            DriveItem parent,
            String originalName,
            String storedName,
            String storagePath,
            long fileSize,
            String sourceType,
            String sourceReference
    ) {
        item.setOwner(owner);
        item.setParent(parent);
        item.setItemType(DriveItemType.FILE);
        item.setOriginalName(safeName(originalName, "travel-file"));
        item.setStoredName(safeName(storedName, storedNameFromPath(storagePath)));
        item.setExtension(extensionFromName(originalName, storagePath));
        item.setStoragePath(storagePath);
        item.setFileSize(Math.max(0L, fileSize));
        item.setLockedFile(true);
        item.setSystemManaged(true);
        item.setSourceType(sourceType);
        item.setSourceReference(sourceReference);
        item.setTrashed(false);
        item.setDeletedAt(null);
        if (item.getUploadedAt() == null) {
            item.setUploadedAt(LocalDateTime.now());
        }
    }

    private void removeSourceItems(Long ownerId, String sourceType, String sourceReference) {
        List<DriveItem> targets = driveItemRepository.findAllByOwner_IdAndSourceTypeAndSourceReference(ownerId, sourceType, sourceReference);
        if (targets.isEmpty()) {
            return;
        }
        targets.forEach(this::deleteMetadataTree);
    }

    private void deleteMetadataTree(DriveItem root) {
        List<DriveItem> items = collectMetadataTree(root);
        List<Long> itemIds = items.stream()
                .map(DriveItem::getId)
                .filter(Objects::nonNull)
                .toList();
        if (!itemIds.isEmpty()) {
            driveDownloadLinkRepository.deleteAllByItem_IdIn(itemIds);
            driveItemVersionRepository.deleteAllByItem_IdIn(itemIds);
        }
        List<DriveItem> deleteOrder = new ArrayList<>(items);
        Collections.reverse(deleteOrder);
        driveItemRepository.deleteAll(deleteOrder);
    }

    private List<DriveItem> collectMetadataTree(DriveItem item) {
        List<DriveItem> items = new ArrayList<>();
        items.add(item);
        driveItemRepository.findAllByOwner_Id(item.getOwner().getId()).stream()
                .filter(candidate -> candidate.getParent() != null)
                .filter(candidate -> Objects.equals(candidate.getParent().getId(), item.getId()))
                .forEach(child -> items.addAll(collectMetadataTree(child)));
        return items;
    }

    private String buildPlanFolderName(TravelPlan plan) {
        String name = safeName(plan.getName(), ROOT_FOLDER_NAME);
        if (plan.getStartDate() != null) {
            return plan.getStartDate() + " " + name;
        }
        return name;
    }

    private String safeName(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized)) {
            normalized = fallback;
        }
        normalized = normalized
                .replace('\\', '_')
                .replace('/', '_')
                .replaceAll("\\p{Cntrl}+", "_")
                .trim();
        if (!StringUtils.hasText(normalized)) {
            normalized = fallback;
        }
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }

    private boolean hasLinkableStoragePath(String storagePath) {
        return StringUtils.hasText(storagePath);
    }

    private String storedNameFromPath(String storagePath) {
        if (!StringUtils.hasText(storagePath)) {
            return "travel-file";
        }
        int index = storagePath.lastIndexOf('/');
        return index >= 0 && index < storagePath.length() - 1 ? storagePath.substring(index + 1) : storagePath;
    }

    private String extensionFromName(String originalName, String storagePath) {
        String candidate = StringUtils.hasText(originalName) ? originalName : storedNameFromPath(storagePath);
        int index = candidate.lastIndexOf('.');
        if (index >= 0 && index < candidate.length() - 1) {
            return candidate.substring(index + 1).toLowerCase(Locale.ROOT).replaceAll("^\\.+", "");
        }
        return "bin";
    }

    public record TravelLinkedFile(
            String originalFileName,
            String storagePath,
            String contentType,
            long fileSize
    ) {
    }
}