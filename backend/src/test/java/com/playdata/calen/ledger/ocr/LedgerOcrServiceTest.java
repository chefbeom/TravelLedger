package com.playdata.calen.ledger.ocr;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.service.AppUserService;
import com.playdata.calen.common.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

@ExtendWith(MockitoExtension.class)
class LedgerOcrServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private AppUserService appUserService;

    @Mock
    private LedgerOcrRemoteClient remoteClient;

    private LedgerOcrService service;

    @BeforeEach
    void setUp() {
        LedgerOcrProperties properties = new LedgerOcrProperties();
        properties.setEnabled(true);
        properties.setWorkflowUrl("https://ocr.example.internal/webhook");
        properties.setMaxFileSize(DataSize.ofBytes(4));
        service = new LedgerOcrService(appUserService, properties, remoteClient);
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

    private void stubUser() {
        AppUser user = new AppUser();
        user.setId(USER_ID);
        when(appUserService.getRequiredUser(USER_ID)).thenReturn(user);
    }
}