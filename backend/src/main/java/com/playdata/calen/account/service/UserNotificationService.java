package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.UserNotification;
import com.playdata.calen.account.dto.UserNotificationCreateRequest;
import com.playdata.calen.account.dto.UserNotificationPageResponse;
import com.playdata.calen.account.dto.UserNotificationReadResponse;
import com.playdata.calen.account.dto.UserNotificationResponse;
import com.playdata.calen.account.repository.UserNotificationRepository;
import com.playdata.calen.common.exception.NotFoundException;
import java.time.LocalDateTime;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserNotificationService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_METADATA_LENGTH = 4000;
    private static final String REDACTED = "[redacted]";
    private static final Pattern SENSITIVE_METADATA_FIELD = Pattern.compile(
            "(\"(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|client[_-]?secret|password|"
                    + "signed[_-]?url|presigned[_-]?url|workflow[_-]?url|authorization|credential)\"\\s*:\\s*\")([^\"]*)(\")",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SENSITIVE_QUERY_PARAM = Pattern.compile(
            "((?:[?&]|%3f|%26)(?:x-amz-signature|x-amz-credential|x-amz-security-token|x-goog-signature|"
                    + "x-goog-credential|x-goog-security-token|signature|sig|token|access_token|api[_-]?key|apikey|"
                    + "secret|password)=)[^\"&\\s]+",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(bearer\\s+)[A-Za-z0-9._~+/=-]+",
            Pattern.CASE_INSENSITIVE
    );

    private final UserNotificationRepository userNotificationRepository;
    private final UserNotificationPreferenceService userNotificationPreferenceService;

    public UserNotificationPageResponse getNotifications(Long userId, Boolean unreadOnly, Integer page, Integer size) {
        Pageable pageable = PageRequest.of(
                Math.max(page == null ? 0 : page, 0),
                normalizeSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt", "id")
        );
        Page<UserNotification> result = Boolean.TRUE.equals(unreadOnly)
                ? userNotificationRepository.findAllByOwnerIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId, pageable)
                : userNotificationRepository.findAllByOwnerIdOrderByCreatedAtDescIdDesc(userId, pageable);
        long unreadCount = userNotificationRepository.countByOwnerIdAndReadAtIsNull(userId);
        return new UserNotificationPageResponse(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                unreadCount
        );
    }

    @Transactional
    public UserNotificationResponse createNotification(Long userId, UserNotificationCreateRequest request) {
        UserNotification notification = new UserNotification();
        notification.setOwnerId(userId);
        notification.setType(truncate(required(request.type(), "Notification type is required."), 60));
        notification.setTitle(truncate(required(request.title(), "Notification title is required."), 160));
        notification.setMessage(truncate(required(request.message(), "Notification message is required."), 1000));
        notification.setTargetUrl(truncate(redactSensitiveValues(request.targetUrl()), 500));
        notification.setMetadataJson(truncate(redactSensitiveValues(request.metadataJson()), MAX_METADATA_LENGTH));
        return toResponse(userNotificationRepository.save(notification));
    }

    @Transactional
    public UserNotificationResponse createSystemNotification(
            Long userId,
            String type,
            String title,
            String message,
            String targetUrl,
            String metadataJson
    ) {
        if (!userNotificationPreferenceService.isSystemNotificationEnabled(userId, type)) {
            return null;
        }
        return createNotification(userId, new UserNotificationCreateRequest(
                type,
                title,
                message,
                targetUrl,
                metadataJson
        ));
    }

    @Transactional
    public UserNotificationReadResponse markRead(Long userId, Long notificationId) {
        UserNotification notification = userNotificationRepository.findByIdAndOwnerId(notificationId, userId)
                .orElseThrow(() -> new NotFoundException("Notification was not found."));
        LocalDateTime processedAt = LocalDateTime.now();
        int updatedCount = notification.isRead() ? 0 : 1;
        notification.markRead(processedAt);
        long unreadCount = userNotificationRepository.countByOwnerIdAndReadAtIsNull(userId);
        return new UserNotificationReadResponse(updatedCount, unreadCount, processedAt);
    }

    @Transactional
    public UserNotificationReadResponse markAllRead(Long userId) {
        LocalDateTime processedAt = LocalDateTime.now();
        int updatedCount = userNotificationRepository.markAllUnreadAsRead(userId, processedAt);
        long unreadCount = userNotificationRepository.countByOwnerIdAndReadAtIsNull(userId);
        return new UserNotificationReadResponse(updatedCount, unreadCount, processedAt);
    }

    private UserNotificationResponse toResponse(UserNotification notification) {
        return new UserNotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getTargetUrl(),
                notification.getMetadataJson(),
                notification.isRead(),
                notification.getReadAt(),
                notification.getCreatedAt()
        );
    }

    private int normalizeSize(Integer size) {
        int value = size == null ? DEFAULT_PAGE_SIZE : size;
        if (value < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(value, MAX_PAGE_SIZE);
    }

    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String redactSensitiveValues(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String redacted = value.trim();
        redacted = SENSITIVE_METADATA_FIELD.matcher(redacted).replaceAll("$1" + REDACTED + "$3");
        redacted = SENSITIVE_QUERY_PARAM.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("$1" + REDACTED);
        return redacted;
    }
}