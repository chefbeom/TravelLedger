package com.playdata.calen.drive.service;

import com.playdata.calen.drive.domain.DriveDownloadLinkAccessLog;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveDownloadLinkAccessLogRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DriveDownloadLinkAccessLogService {

    private static final int MAX_STATUS_LENGTH = 32;
    private static final int MAX_CLIENT_ADDRESS_LENGTH = 64;
    private static final int MAX_USER_AGENT_LENGTH = 255;

    private final DriveDownloadLinkAccessLogRepository driveDownloadLinkAccessLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            Long linkId,
            Long itemId,
            Long ownerId,
            String token,
            String status,
            AccessMetadata metadata
    ) {
        DriveDownloadLinkAccessLog log = new DriveDownloadLinkAccessLog();
        log.setLinkId(linkId);
        log.setItemId(itemId);
        log.setOwnerId(ownerId);
        log.setStatus(truncate(hasText(status) ? status : "unknown", MAX_STATUS_LENGTH));
        log.setTokenFingerprint(tokenFingerprint(token));
        if (metadata != null) {
            log.setClientAddress(truncate(metadata.clientAddress(), MAX_CLIENT_ADDRESS_LENGTH));
            log.setUserAgent(truncate(metadata.userAgent(), MAX_USER_AGENT_LENGTH));
        }
        driveDownloadLinkAccessLogRepository.save(log);
    }

    @Transactional(readOnly = true)
    public List<DriveDtos.DownloadLinkAccessLogResponse> listRecentLogs(Long ownerId, Long linkId) {
        return driveDownloadLinkAccessLogRepository
                .findTop50ByLinkIdAndOwnerIdOrderByAccessedAtDesc(linkId, ownerId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private DriveDtos.DownloadLinkAccessLogResponse toResponse(DriveDownloadLinkAccessLog log) {
        return DriveDtos.DownloadLinkAccessLogResponse.builder()
                .id(log.getId())
                .linkId(log.getLinkId())
                .itemId(log.getItemId())
                .status(log.getStatus())
                .clientAddress(log.getClientAddress())
                .userAgent(log.getUserAgent())
                .accessedAt(log.getAccessedAt())
                .build();
    }

    private String tokenFingerprint(String token) {
        String normalized = hasText(token) ? token.trim() : "blank";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available.", ex);
        }
    }

    private String truncate(String value, int maxLength) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record AccessMetadata(String clientAddress, String userAgent) {
    }
}