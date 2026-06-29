package com.playdata.calen.account.web;

import com.playdata.calen.account.dto.UserNotificationCreateRequest;
import com.playdata.calen.account.dto.UserNotificationPageResponse;
import com.playdata.calen.account.dto.UserNotificationReadResponse;
import com.playdata.calen.account.dto.UserNotificationResponse;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.account.service.UserNotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class UserNotificationController {

    private final UserNotificationService userNotificationService;

    @GetMapping
    public UserNotificationPageResponse getNotifications(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @RequestParam(required = false) Boolean unreadOnly,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return userNotificationService.getNotifications(currentUser.userId(), unreadOnly, page, size);
    }

    @PostMapping
    public UserNotificationResponse createNotification(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @Valid @RequestBody UserNotificationCreateRequest request
    ) {
        return userNotificationService.createNotification(currentUser.userId(), request);
    }

    @PatchMapping("/{notificationId}/read")
    public UserNotificationReadResponse markRead(
            @AuthenticationPrincipal AppUserPrincipal currentUser,
            @PathVariable Long notificationId
    ) {
        return userNotificationService.markRead(currentUser.userId(), notificationId);
    }

    @PatchMapping("/read-all")
    public UserNotificationReadResponse markAllRead(@AuthenticationPrincipal AppUserPrincipal currentUser) {
        return userNotificationService.markAllRead(currentUser.userId());
    }
}