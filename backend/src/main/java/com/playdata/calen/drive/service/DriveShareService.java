package com.playdata.calen.drive.service;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.common.media.ImageThumbnailService;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveItemType;
import com.playdata.calen.drive.domain.DriveShare;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.drive.repository.DriveShareRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class DriveShareService {

    private final DriveShareRepository driveShareRepository;
    private final DriveItemRepository driveItemRepository;
    private final AppUserRepository appUserRepository;
    private final DriveStorageService driveStorageService;
    private final DriveService driveService;
    private final ImageThumbnailService imageThumbnailService;

    public DriveShareService(
            DriveShareRepository driveShareRepository,
            DriveItemRepository driveItemRepository,
            AppUserRepository appUserRepository,
            DriveStorageService driveStorageService,
            DriveService driveService,
            ImageThumbnailService imageThumbnailService
    ) {
        this.driveShareRepository = driveShareRepository;
        this.driveItemRepository = driveItemRepository;
        this.appUserRepository = appUserRepository;
        this.driveStorageService = driveStorageService;
        this.driveService = driveService;
        this.imageThumbnailService = imageThumbnailService;
    }

    public List<DriveDtos.SharedFileResponse> getReceivedShares(Long userId) {
        return driveShareRepository.findAllByRecipient_IdOrderByCreatedAtDesc(userId).stream()
                .filter(share -> share.getItem() != null && share.getItem().isFile())
                .map(this::toSharedFileResponse)
                .toList();
    }

    public List<DriveDtos.SentShareGroupResponse> getSentShares(Long userId) {
        Map<Long, List<DriveShare>> grouped = driveShareRepository.findAllByOwner_IdOrderByCreatedAtDesc(userId).stream()
                .filter(share -> share.getItem() != null && share.getItem().isFile())
                .collect(Collectors.groupingBy(
                        share -> share.getItem().getId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<DriveDtos.SentShareGroupResponse> responses = new ArrayList<>();
        grouped.forEach((fileId, shares) -> {
            DriveItem item = shares.get(0).getItem();
            responses.add(DriveDtos.SentShareGroupResponse.builder()
                    .fileId(fileId)
                    .fileOriginName(item.getOriginalName())
                    .fileFormat(item.getExtension())
                    .fileSize(item.getFileSize())
                    .recipients(shares.stream()
                            .map(this::toShareInfoResponse)
                            .sorted(Comparator.comparing(DriveDtos.ShareInfoResponse::createdAt).reversed())
                            .toList())
                    .build());
        });
        return responses;
    }

    public List<DriveDtos.ShareInfoResponse> getShareInfo(Long userId, Long fileId) {
        DriveItem ownedItem = driveService.getOwnedFile(userId, fileId);
        return driveShareRepository.findAllByItem_Id(ownedItem.getId()).stream()
                .map(this::toShareInfoResponse)
                .sorted(Comparator.comparing(DriveDtos.ShareInfoResponse::createdAt).reversed())
                .toList();
    }

    public List<DriveDtos.ShareInfoResponse> searchRecipients(Long userId, String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.length() < 1) {
            return List.of();
        }

        return appUserRepository.searchActiveUsersForInvitation(userId, normalized, PageRequest.of(0, 10)).stream()
                .map(user -> DriveDtos.ShareInfoResponse.builder()
                        .shareId(null)
                        .recipientUserId(user.getId())
                        .recipientLoginId(user.getLoginId())
                        .recipientDisplayName(user.getDisplayName())
                        .createdAt(null)
                        .build())
                .toList();
    }

    @Transactional
    public DriveDtos.ActionResponse shareFiles(Long userId, List<Long> fileIds, String recipientLoginId) {
        AppUser owner = driveService.getOwner(userId);
        AppUser recipient = appUserRepository.findByLoginId(safeLoginId(recipientLoginId))
                .filter(AppUser::isActive)
                .orElseThrow(() -> new NotFoundException("공유할 사용자를 찾지 못했습니다."));

        if (Objects.equals(owner.getId(), recipient.getId())) {
            throw new BadRequestException("본인에게는 공유할 수 없습니다.");
        }

        List<Long> normalizedIds = normalizeFileIds(fileIds);
        int affected = 0;
        for (Long fileId : normalizedIds) {
            DriveItem item = driveService.getOwnedFile(userId, fileId);
            if (item.isTrashed()) {
                throw new BadRequestException("휴지통에 있는 파일은 공유할 수 없습니다.");
            }

            if (driveShareRepository.findByItem_IdAndRecipient_Id(item.getId(), recipient.getId()).isPresent()) {
                continue;
            }

            DriveShare share = new DriveShare();
            share.setItem(item);
            share.setOwner(owner);
            share.setRecipient(recipient);
            driveShareRepository.save(share);
            item.setSharedFile(true);
            affected += 1;
        }

        return DriveDtos.ActionResponse.builder()
                .action("share")
                .affectedCount(affected)
                .build();
    }

    @Transactional
    public DriveDtos.ActionResponse cancelShare(Long userId, List<Long> fileIds, String recipientLoginId) {
        AppUser recipient = appUserRepository.findByLoginId(safeLoginId(recipientLoginId))
                .orElseThrow(() -> new NotFoundException("공유 대상을 찾지 못했습니다."));

        int affected = 0;
        for (Long fileId : normalizeFileIds(fileIds)) {
            DriveItem item = driveService.getOwnedFile(userId, fileId);
            if (driveShareRepository.findByItem_IdAndRecipient_Id(item.getId(), recipient.getId()).isPresent()) {
                driveShareRepository.deleteByItem_IdAndRecipient_Id(item.getId(), recipient.getId());
                item.setSharedFile(driveShareRepository.countByItem_Id(item.getId()) > 0);
                affected += 1;
            }
        }
        refreshSharedFlags(userId, fileIds);
        return DriveDtos.ActionResponse.builder()
                .action("cancel-share")
                .affectedCount(affected)
                .build();
    }

    @Transactional
    public DriveDtos.ActionResponse cancelAllShares(Long userId, List<Long> fileIds) {
        int affected = 0;
        for (Long fileId : normalizeFileIds(fileIds)) {
            DriveItem item = driveService.getOwnedFile(userId, fileId);
            List<DriveShare> shares = driveShareRepository.findAllByItem_Id(item.getId());
            if (!shares.isEmpty()) {
                affected += shares.size();
                driveShareRepository.deleteAll(shares);
            }
            item.setSharedFile(false);
        }
        return DriveDtos.ActionResponse.builder()
                .action("cancel-all-shares")
                .affectedCount(affected)
                .build();
    }

    @Transactional
    public DriveDtos.FileItemResponse saveSharedFileToDrive(Long userId, Long fileId, Long parentId) {
        DriveShare share = driveShareRepository.findByItem_IdAndRecipient_Id(fileId, userId)
                .orElseThrow(() -> new NotFoundException("공유된 파일을 찾지 못했습니다."));

        DriveItem sourceItem = share.getItem();
        if (!sourceItem.isFile()) {
            throw new BadRequestException("파일만 내 드라이브로 저장할 수 있습니다.");
        }

        DriveItem parent = resolveOwnedFolder(userId, parentId);
        String targetObjectKey = "drive/" + userId + "/" + sourceItem.getStoredName();
        if (Objects.equals(sourceItem.getOwner().getId(), userId)) {
            targetObjectKey = "drive/" + userId + "/" + System.currentTimeMillis() + "-" + sourceItem.getStoredName();
        }
        driveStorageService.copyObject(sourceItem.getStoragePath(), targetObjectKey);

        DriveItem copiedItem = new DriveItem();
        copiedItem.setOwner(driveService.getOwner(userId));
        copiedItem.setParent(parent);
        copiedItem.setItemType(DriveItemType.FILE);
        copiedItem.setOriginalName(sourceItem.getOriginalName());
        copiedItem.setExtension(sourceItem.getExtension());
        copiedItem.setStoredName(extractStoredName(targetObjectKey));
        copiedItem.setStoragePath(targetObjectKey);
        copiedItem.setFileSize(sourceItem.getFileSize());
        copiedItem.setSharedFile(false);
        return driveService.toItemResponse(driveItemRepository.save(copiedItem));
    }

    public DriveService.DriveFilePayload downloadSharedFile(Long userId, Long fileId) {
        DriveItem item = getSharedFile(userId, fileId);
        byte[] bytes = driveStorageService.loadObjectBytes(item.getStoragePath());
        item.setLastAccessedAt(LocalDateTime.now());
        return new DriveService.DriveFilePayload(
                bytes,
                resolveContentType(item.getExtension()),
                item.getOriginalName(),
                item.getFileSize()
        );
    }

    public String getSharedFileDownloadUrl(Long userId, Long fileId) {
        return driveStorageService.generateDownloadUrl(getSharedFile(userId, fileId).getStoragePath());
    }

    public DriveService.ThumbnailPayload loadSharedThumbnail(Long userId, Long fileId, Integer width) {
        DriveItem item = getSharedFile(userId, fileId);
        String contentType = resolveContentType(item.getExtension());
        if (!contentType.startsWith("image/")) {
            return null;
        }

        byte[] sourceBytes = driveStorageService.loadObjectBytes(item.getStoragePath());
        return imageThumbnailService.createPreparedThumbnails(sourceBytes, contentType, List.of(width == null ? 320 : width)).stream()
                .findFirst()
                .map(thumbnail -> new DriveService.ThumbnailPayload(
                        thumbnail.bytes(),
                        thumbnail.contentType(),
                        "\"" + item.getId() + "-" + item.getLastModifiedAt() + "-" + thumbnail.width() + "\"",
                        item.getLastModifiedAt() != null
                                ? item.getLastModifiedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                : System.currentTimeMillis()
                ))
                .orElse(null);
    }

    private DriveItem getSharedFile(Long userId, Long fileId) {
        DriveShare share = driveShareRepository.findByItem_IdAndRecipient_Id(fileId, userId)
                .orElseThrow(() -> new NotFoundException("공유된 파일을 찾지 못했습니다."));
        if (!share.getItem().isFile()) {
            throw new BadRequestException("파일만 사용할 수 있습니다.");
        }
        return share.getItem();
    }

    private void refreshSharedFlags(Long userId, List<Long> fileIds) {
        for (Long fileId : normalizeFileIds(fileIds)) {
            DriveItem item = driveService.getOwnedFile(userId, fileId);
            item.setSharedFile(driveShareRepository.countByItem_Id(item.getId()) > 0);
        }
    }

    private DriveDtos.SharedFileResponse toSharedFileResponse(DriveShare share) {
        DriveItem item = share.getItem();
        return DriveDtos.SharedFileResponse.builder()
                .id(share.getId())
                .fileId(item.getId())
                .fileOriginName(item.getOriginalName())
                .fileFormat(item.getExtension())
                .fileSize(item.getFileSize())
                .ownerLoginId(share.getOwner().getLoginId())
                .ownerDisplayName(share.getOwner().getDisplayName())
                .sharedAt(share.getCreatedAt())
                .downloadUrl("/api/file/share/shared/" + item.getId() + "/download-link")
                .thumbnailUrl("/api/file/share/shared/" + item.getId() + "/thumbnail")
                .build();
    }

    private DriveDtos.ShareInfoResponse toShareInfoResponse(DriveShare share) {
        return DriveDtos.ShareInfoResponse.builder()
                .shareId(share.getId())
                .recipientUserId(share.getRecipient().getId())
                .recipientLoginId(share.getRecipient().getLoginId())
                .recipientDisplayName(share.getRecipient().getDisplayName())
                .createdAt(share.getCreatedAt())
                .build();
    }

    private DriveItem resolveOwnedFolder(Long ownerId, Long parentId) {
        if (parentId == null) {
            return null;
        }
        DriveItem parent = driveItemRepository.findByIdAndOwner_Id(parentId, ownerId)
                .orElseThrow(() -> new NotFoundException("대상 폴더를 찾지 못했습니다."));
        if (!parent.isFolder()) {
            throw new BadRequestException("폴더만 상위 위치로 선택할 수 있습니다.");
        }
        return parent;
    }

    private List<Long> normalizeFileIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new BadRequestException("파일을 선택해 주세요.");
        }
        return fileIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    private String safeLoginId(String loginId) {
        String normalized = loginId == null ? "" : loginId.trim();
        if (!StringUtils.hasText(normalized)) {
            throw new BadRequestException("공유 대상 로그인 ID를 입력해 주세요.");
        }
        return normalized;
    }

    private String extractStoredName(String objectKey) {
        int separatorIndex = objectKey.lastIndexOf('/');
        return separatorIndex >= 0 ? objectKey.substring(separatorIndex + 1) : objectKey;
    }

    private String resolveContentType(String extension) {
        String normalized = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG_VALUE;
            case "png" -> MediaType.IMAGE_PNG_VALUE;
            case "gif" -> MediaType.IMAGE_GIF_VALUE;
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "svg" -> "image/svg+xml";
            case "pdf" -> MediaType.APPLICATION_PDF_VALUE;
            case "txt", "md", "csv", "json" -> MediaType.TEXT_PLAIN_VALUE;
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }
}
