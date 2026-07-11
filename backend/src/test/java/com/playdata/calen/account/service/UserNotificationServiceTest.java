package com.playdata.calen.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.UserNotification;
import com.playdata.calen.account.dto.UserNotificationCreateRequest;
import com.playdata.calen.account.dto.UserNotificationResponse;
import com.playdata.calen.account.repository.UserNotificationRepository;
import com.playdata.calen.common.exception.NotFoundException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserNotificationServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private UserNotificationRepository userNotificationRepository;

    @Mock
    private UserNotificationPreferenceService userNotificationPreferenceService;

    private UserNotificationService userNotificationService;

    @BeforeEach
    void setUp() {
        userNotificationService = new UserNotificationService(
                userNotificationRepository,
                userNotificationPreferenceService
        );
    }

    @Test
    void createNotificationRedactsSensitiveMetadataAndTargetUrlBeforeSaving() {
        when(userNotificationRepository.save(any(UserNotification.class))).thenAnswer(invocation -> {
            UserNotification notification = invocation.getArgument(0);
            notification.setId(10L);
            notification.setCreatedAt(LocalDateTime.of(2026, 6, 30, 9, 0));
            return notification;
        });

        UserNotificationResponse response = userNotificationService.createNotification(
                USER_ID,
                new UserNotificationCreateRequest(
                        "BACKUP_FAILED",
                        "Backup failed",
                        "Backup rehearsal needs attention.",
                        "/admin/backups?X-Amz-Signature=raw-signature&safe=true",
                        """
                                {"apiKey":"sk-live-secret","signedUrl":"https://files.example.test/object?X-Amz-Signature=abc123","note":"Bearer eyJ.secret"}
                                """
                )
        );

        String metadata = response.metadataJson();
        assertThat(metadata)
                .contains("\"apiKey\":\"[redacted]\"")
                .contains("\"signedUrl\":\"[redacted]\"")
                .contains("Bearer [redacted]");
        assertThat(metadata.toLowerCase(Locale.ROOT))
                .doesNotContain("sk-live-secret")
                .doesNotContain("abc123")
                .doesNotContain("eyj.secret");
        assertThat(response.targetUrl())
                .isEqualTo("/admin/backups?X-Amz-Signature=[redacted]&safe=true");
    }

    @Test
    void markReadUsesOwnerScopedLookup() {
        when(userNotificationRepository.findByIdAndOwnerId(99L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userNotificationService.markRead(USER_ID, 99L))
                .isInstanceOf(NotFoundException.class);

        verify(userNotificationRepository).findByIdAndOwnerId(99L, USER_ID);
        verify(userNotificationRepository, never()).countByOwnerIdAndReadAtIsNull(USER_ID);
    }
}