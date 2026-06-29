package com.playdata.calen.ledger.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.account.service.UserNotificationService;
import com.playdata.calen.common.exception.BadRequestException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

@ExtendWith(MockitoExtension.class)
class LedgerOcrServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private AppUserService appUserService;

    @Mock
    private LedgerOcrRemoteClient remoteClient;

    @Mock
    private UserNotificationService userNotificationService;

    private LedgerOcrService service;

    @BeforeEach
    void setUp() {
        LedgerOcrProperties properties = new LedgerOcrProperties();
        properties.setEnabled(true);
        properties.setWorkflowUrl("https://ocr.example.internal/webhook");
        properties.setMaxFileSize(DataSize.ofBytes(4));
        service = new LedgerOcrService(appUserService, properties, remoteClient, userNotificationService);
    }

    @Test
    void analyzeRejectsOversizedFileBeforeRemoteCall() {
        stubUser();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.png",
                "image/png",
                new byte[] {1, 2, 3, 4, 5}
        );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Receipt image exceeds the OCR upload size limit.");

        verifyNoInteractions(remoteClient);
    }

    @Test
    void analyzeRejectsImageExtensionWithNonImageMimeBeforeRemoteCall() {
        stubUser();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.jpg",
                "application/pdf",
                new byte[] {1, 2, 3}
        );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only image files can be analyzed.");

        verifyNoInteractions(remoteClient);
    }

    @Test
    void analyzeRejectsImageMimeWithNonImageExtensionBeforeRemoteCall() {
        stubUser();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.txt",
                "image/png",
                new byte[] {1, 2, 3}
        );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only image files can be analyzed.");

        verifyNoInteractions(remoteClient);
    }

    @Test
    void analyzeRejectsMismatchedImageMimeAndExtensionBeforeRemoteCall() {
        stubUser();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.jpg",
                "image/png",
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
        );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only image files can be analyzed.");

        verifyNoInteractions(remoteClient);
    }

    @Test
    void analyzeRejectsFakeImageBytesBeforeRemoteCall() {
        stubUser();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.png",
                "image/png",
                new byte[] {'n', 'o', 't'}
        );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only image files can be analyzed.");

        verifyNoInteractions(remoteClient);
    }

    @Test
    void analyzeRecordsInvalidFileMetricWhenUploadValidationFails() {
        stubUser();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(service, "meterRegistry", meterRegistry);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.txt",
                "image/png",
                new byte[] {1, 2, 3}
        );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Only image files can be analyzed.");

        assertThat(meterRegistry.get("calen.ledger.ocr.requests")
                .tag("status", "failure")
                .tag("reason", "invalid_file")
                .counter()
                .count()).isEqualTo(1.0);
        verifyNoInteractions(remoteClient);
    }

    @Test
    void analyzeCreatesBoundedNotificationForRemoteFailureWithoutMaskingOriginalError() {
        stubUser();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "receipt.jpg",
                "image/jpeg",
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
        );
        when(remoteClient.analyze(file, "RECEIPT"))
                .thenThrow(new BadRequestException("OCR analysis server is unavailable. Check the OCR service and network."));
        doThrow(new IllegalStateException("notification unavailable"))
                .when(userNotificationService)
                .createSystemNotification(
                        eq(USER_ID),
                        eq("AI_OR_OCR_FAILED"),
                        eq("OCR analysis failed"),
                        contains("Receipt OCR could not be completed"),
                        eq("/calendar?receiptOcr=1"),
                        eq("{\"reason\":\"bad_request\"}")
                );

        assertThatThrownBy(() -> service.analyze(USER_ID, file, "RECEIPT"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("OCR analysis server is unavailable. Check the OCR service and network.");

        verify(userNotificationService).createSystemNotification(
                eq(USER_ID),
                eq("AI_OR_OCR_FAILED"),
                eq("OCR analysis failed"),
                contains("Receipt OCR could not be completed"),
                eq("/calendar?receiptOcr=1"),
                eq("{\"reason\":\"bad_request\"}")
        );
    }

    private void stubUser() {
        AppUser user = new AppUser();
        user.setId(USER_ID);
        when(appUserService.getRequiredUser(USER_ID)).thenReturn(user);
    }
}