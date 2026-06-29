package com.playdata.calen.drive.service;

import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.drive.domain.DriveDownloadLink;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveDownloadLinkRepository;
import com.playdata.calen.drive.service.DriveDownloadLinkAccessLogService.AccessMetadata;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DriveDownloadLinkService {

    private static final int DEFAULT_EXPIRES_IN_MINUTES = 60;
    private static final int MIN_EXPIRES_IN_MINUTES = 5;
    private static final int MAX_EXPIRES_IN_MINUTES = 60 * 24 * 30;
    private static final int DEFAULT_MAX_DOWNLOADS = 10;
    private static final int MIN_MAX_DOWNLOADS = 1;
    private static final int MAX_MAX_DOWNLOADS = 1000;

    private final DriveDownloadLinkRepository driveDownloadLinkRepository;
    private final DriveService driveService;
    private final DriveStorageService driveStorageService;
    private final DriveDownloadLinkAccessLogService driveDownloadLinkAccessLogService;

    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public DriveDtos.DownloadLinkResponse createLink(Long userId, Long fileId, DriveDtos.DownloadLinkCreateRequest request) {
        DriveItem item = driveService.getOwnedFile(userId, fileId);
        if (item.isTrashed()) {
            throw new BadRequestException("Items in trash cannot be shared by link.");
        }
        driveService.ensureUnlocked(item);

        LocalDateTime now = LocalDateTime.now();
        DriveDownloadLink link = new DriveDownloadLink();
        link.setToken(generateUniqueToken());
        link.setOwner(item.getOwner());
        link.setItem(item);
        link.setCreatedAt(now);
        link.setExpiresAt(now.plusMinutes(resolveExpiresInMinutes(request)));
        link.setMaxDownloads(resolveMaxDownloads(request));
        link.setDownloadCount(0);

        return toResponse(driveDownloadLinkRepository.save(link));
    }

    public List<DriveDtos.DownloadLinkResponse> listLinks(Long userId, Long fileId) {
        DriveItem item = driveService.getOwnedFile(userId, fileId);
        return driveDownloadLinkRepository.findAllByItem_IdAndOwner_IdOrderByCreatedAtDesc(item.getId(), userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public DriveDtos.DownloadLinkResponse revokeLink(Long userId, Long linkId) {
        DriveDownloadLink link = driveDownloadLinkRepository.findByIdAndOwner_Id(linkId, userId)
                .orElseThrow(() -> new NotFoundException("Download link was not found."));
        driveService.ensureUnlocked(link.getItem());
        if (link.getRevokedAt() == null) {
            link.setRevokedAt(LocalDateTime.now());
        }
        return toResponse(link);
    }

    public List<DriveDtos.DownloadLinkAccessLogResponse> listAccessLogs(Long userId, Long linkId) {
        driveDownloadLinkRepository.findByIdAndOwner_Id(linkId, userId)
                .orElseThrow(() -> new NotFoundException("Download link was not found."));
        return driveDownloadLinkAccessLogService.listRecentLogs(userId, linkId);
    }

    @Transactional
    public DriveService.DriveFilePayload downloadByToken(String token) {
        return downloadByToken(token, null);
    }

    @Transactional
    public DriveService.DriveFilePayload downloadByToken(String token, AccessMetadata metadata) {
        DriveDownloadLink link = resolveAvailableDownloadLink(token, metadata);
        DriveItem item = link.getItem();
        byte[] bytes = driveStorageService.loadObjectBytes(item.getStoragePath());
        String contentType = driveService.resolveContentType(item.getExtension());
        recordPublicDownloadLinkRequest("success");
        recordPublicDownloadLinkAccess(link, token, "success", metadata);
        return new DriveService.DriveFilePayload(
                bytes,
                contentType,
                item.getOriginalName(),
                item.getFileSize()
        );
    }

    @Transactional
    public String resolveDownloadUrlByToken(String token) {
        return resolveDownloadUrlByToken(token, null);
    }

    @Transactional
    public String resolveDownloadUrlByToken(String token, AccessMetadata metadata) {
        DriveDownloadLink link = resolveAvailableDownloadLink(token, metadata);
        DriveItem item = link.getItem();
        String downloadUrl = driveStorageService.generateDownloadUrl(
                item.getStoragePath(),
                item.getOriginalName(),
                driveService.resolveContentType(item.getExtension())
        );
        recordPublicDownloadLinkRequest("success");
        recordPublicDownloadLinkAccess(link, token, "success", metadata);
        return downloadUrl;
    }

    private DriveDownloadLink resolveAvailableDownloadLink(String token, AccessMetadata metadata) {
        if (token == null || token.isBlank()) {
            recordPublicDownloadLinkRequest("invalid");
            recordPublicDownloadLinkAccess(null, token, "invalid", metadata);
            throw new NotFoundException("Download link was not found.");
        }

        DriveDownloadLink link = driveDownloadLinkRepository.findByToken(token.trim()).orElse(null);
        if (link == null) {
            recordPublicDownloadLinkRequest("invalid");
            recordPublicDownloadLinkAccess(null, token, "invalid", metadata);
            throw new NotFoundException("Download link was not found.");
        }

        LocalDateTime now = LocalDateTime.now();
        DriveItem item = link.getItem();
        if (link.getRevokedAt() != null) {
            recordPublicDownloadLinkRequest("revoked");
            recordPublicDownloadLinkAccess(link, token, "revoked", metadata);
            throw new BadRequestException("Download link is expired or no longer available.");
        }
        if (link.isExpired(now)) {
            recordPublicDownloadLinkRequest("expired");
            recordPublicDownloadLinkAccess(link, token, "expired", metadata);
            throw new BadRequestException("Download link is expired or no longer available.");
        }
        if (link.isDownloadLimitReached()) {
            recordPublicDownloadLinkRequest("limit_reached");
            recordPublicDownloadLinkAccess(link, token, "limit_reached", metadata);
            throw new BadRequestException("Download link is expired or no longer available.");
        }
        if (item == null || !item.isFile() || item.getStoragePath() == null || item.getStoragePath().isBlank()) {
            recordPublicDownloadLinkRequest("invalid_item");
            recordPublicDownloadLinkAccess(link, token, "invalid_item", metadata);
            throw new BadRequestException("Download link is expired or no longer available.");
        }
        if (item.isTrashed()) {
            recordPublicDownloadLinkRequest("trashed");
            recordPublicDownloadLinkAccess(link, token, "trashed", metadata);
            throw new BadRequestException("Download link is expired or no longer available.");
        }

        link.setDownloadCount(link.getDownloadCount() + 1);
        link.setLastAccessedAt(now);
        item.setLastAccessedAt(now);
        return link;
    }

    private DriveDtos.DownloadLinkResponse toResponse(DriveDownloadLink link) {
        LocalDateTime now = LocalDateTime.now();
        return DriveDtos.DownloadLinkResponse.builder()
                .id(link.getId())
                .downloadUrl("/api/file/public-download/" + link.getToken())
                .createdAt(link.getCreatedAt())
                .expiresAt(link.getExpiresAt())
                .maxDownloads(link.getMaxDownloads())
                .downloadCount(link.getDownloadCount())
                .lastAccessedAt(link.getLastAccessedAt())
                .revokedAt(link.getRevokedAt())
                .available(link.getRevokedAt() == null && !link.isExpired(now) && !link.isDownloadLimitReached())
                .build();
    }

    private void recordPublicDownloadLinkRequest(String status) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("calen.public.download.link.requests")
                .description("Public download link requests")
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    private void recordPublicDownloadLinkAccess(
            DriveDownloadLink link,
            String token,
            String status,
            AccessMetadata metadata
    ) {
        Long linkId = link != null ? link.getId() : null;
        Long itemId = link != null && link.getItem() != null ? link.getItem().getId() : null;
        Long ownerId = link != null && link.getOwner() != null ? link.getOwner().getId() : null;
        try {
            driveDownloadLinkAccessLogService.record(linkId, itemId, ownerId, token, status, metadata);
        } catch (RuntimeException ex) {
            log.warn("Failed to record public download link access log: status={}", status, ex);
        }
    }

    private int resolveExpiresInMinutes(DriveDtos.DownloadLinkCreateRequest request) {
        int value = request != null && request.expiresInMinutes() != null
                ? request.expiresInMinutes()
                : DEFAULT_EXPIRES_IN_MINUTES;
        if (value < MIN_EXPIRES_IN_MINUTES || value > MAX_EXPIRES_IN_MINUTES) {
            throw new BadRequestException("Download link expiry must be between 5 minutes and 30 days.");
        }
        return value;
    }

    private int resolveMaxDownloads(DriveDtos.DownloadLinkCreateRequest request) {
        int value = request != null && request.maxDownloads() != null
                ? request.maxDownloads()
                : DEFAULT_MAX_DOWNLOADS;
        if (value < MIN_MAX_DOWNLOADS || value > MAX_MAX_DOWNLOADS) {
            throw new BadRequestException("Download limit must be between 1 and 1000.");
        }
        return value;
    }

    private String generateUniqueToken() {
        for (int attempt = 0; attempt < 5; attempt += 1) {
            byte[] bytes = new byte[32];
            secureRandom.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (!driveDownloadLinkRepository.existsByToken(token)) {
                return token;
            }
        }
        throw new BadRequestException("Could not create a unique download link. Please try again.");
    }
}