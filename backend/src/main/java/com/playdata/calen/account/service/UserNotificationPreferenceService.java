package com.playdata.calen.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.playdata.calen.account.dto.UserLayoutSettingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserNotificationPreferenceService {

    public static final String PREFERENCE_SCOPE = "notification-preferences";

    private final UserLayoutSettingService userLayoutSettingService;

    public boolean isSystemNotificationEnabled(Long userId, String type) {
        if (userId == null) {
            return false;
        }
        try {
            UserLayoutSettingResponse setting = userLayoutSettingService.getLayoutSetting(userId, PREFERENCE_SCOPE);
            JsonNode payload = setting == null ? null : setting.payload();
            if (payload == null || payload.isNull()) {
                return true;
            }
            if (payload.path("enabled").isBoolean() && !payload.path("enabled").asBoolean()) {
                return false;
            }

            JsonNode categories = payload.path("categories");
            String category = resolveCategory(type);
            return !categories.isObject()
                    || !categories.path(category).isBoolean()
                    || categories.path(category).asBoolean();
        } catch (RuntimeException exception) {
            log.warn("Notification preferences could not be loaded; sending notification by default: userId={}, type={}", userId, type, exception);
            return true;
        }
    }

    static String resolveCategory(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase();
        if (normalized.contains("AI") || normalized.contains("OCR") || normalized.contains("HOUSEHOLD") || normalized.contains("GOAL")) {
            return "ledger";
        }
        if (normalized.contains("TRAVEL") || normalized.contains("BUDGET")) {
            return "travel";
        }
        if (normalized.contains("DRIVE") || normalized.contains("FILE") || normalized.contains("SHARE")) {
            return "drive";
        }
        if (normalized.contains("PRIVACY") || normalized.contains("EXPORT") || normalized.contains("SECURITY") || normalized.contains("LOGIN")) {
            return "account";
        }
        return "system";
    }
}