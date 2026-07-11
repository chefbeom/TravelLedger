package com.playdata.calen.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playdata.calen.account.dto.UserLayoutSettingResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserNotificationPreferenceServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private UserLayoutSettingService userLayoutSettingService;

    @Test
    void defaultsToSendingSystemNotificationsWhenNoPreferenceExists() {
        when(userLayoutSettingService.getLayoutSetting(USER_ID, UserNotificationPreferenceService.PREFERENCE_SCOPE))
                .thenReturn(new UserLayoutSettingResponse(
                        UserNotificationPreferenceService.PREFERENCE_SCOPE,
                        1,
                        null,
                        null
                ));

        UserNotificationPreferenceService service = new UserNotificationPreferenceService(userLayoutSettingService);

        assertThat(service.isSystemNotificationEnabled(USER_ID, "AI_IMAGE_ANALYSIS_FAILED")).isTrue();
    }

    @Test
    void disablesEverySystemNotificationWhenMasterToggleIsOff() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("enabled", false);
        when(userLayoutSettingService.getLayoutSetting(USER_ID, UserNotificationPreferenceService.PREFERENCE_SCOPE))
                .thenReturn(new UserLayoutSettingResponse(
                        UserNotificationPreferenceService.PREFERENCE_SCOPE,
                        1,
                        payload,
                        LocalDateTime.now()
                ));

        UserNotificationPreferenceService service = new UserNotificationPreferenceService(userLayoutSettingService);

        assertThat(service.isSystemNotificationEnabled(USER_ID, "TRAVEL_REMINDER")).isFalse();
    }

    @Test
    void disablesOnlyTheConfiguredNotificationCategory() {
        ObjectNode categories = JsonNodeFactory.instance.objectNode();
        categories.put("ledger", false);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("enabled", true);
        payload.set("categories", categories);
        when(userLayoutSettingService.getLayoutSetting(USER_ID, UserNotificationPreferenceService.PREFERENCE_SCOPE))
                .thenReturn(new UserLayoutSettingResponse(
                        UserNotificationPreferenceService.PREFERENCE_SCOPE,
                        1,
                        payload,
                        LocalDateTime.now()
                ));

        UserNotificationPreferenceService service = new UserNotificationPreferenceService(userLayoutSettingService);

        assertThat(service.isSystemNotificationEnabled(USER_ID, "AI_ANALYSIS_DONE")).isFalse();
        assertThat(service.isSystemNotificationEnabled(USER_ID, "TRAVEL_REMINDER")).isTrue();
    }
}