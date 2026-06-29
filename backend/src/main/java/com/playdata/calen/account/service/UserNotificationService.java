package com.playdata.calen.account.service;

import com.playdata.calen.account.domain.UserNotification;
import com.playdata.calen.account.dto.UserNotificationCreateRequest;
import com.playdata.calen.account.dto.UserNotificationPageResponse;
import com.playdata.calen.account.dto.UserNotificationReadResponse;
import com.playdata.calen.account.dto.UserNotificationResponse;
import com.playdata.calen.account.repository.UserNotificationRepository;
import com.playdata.calen.common.exception.NotFoundException;
import java.time.LocalDateTime;
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

    private final UserNotificationRepository userNotificationRepository;

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
        notification.setTargetUrl(truncate(request.targetUrl(), 500));
        notification.setMetadataJson(request.metadataJson());
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
}