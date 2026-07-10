package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.drive.repository.DriveProfileSettingsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class DriveProfileServiceTest {

    private final DriveProfileService service = new DriveProfileService(
            mock(AppUserRepository.class),
            mock(DriveProfileSettingsRepository.class),
            mock(DriveItemRepository.class),
            mock(DriveStorageService.class)
    );

    @Test
    void rejectsOversizedProfileImageBeforeLoadingItIntoStorage() {
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "large.png",
                "image/png",
                new byte[5 * 1024 * 1024 + 1]
        );

        assertThatThrownBy(() -> service.uploadProfileImage(1L, image))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("5 MB");
    }

    @Test
    void rejectsImageMimeTypeWithMismatchedBinarySignature() {
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "payload.png",
                "image/png",
                "<svg><script>alert(1)</script></svg>".getBytes()
        );

        assertThatThrownBy(() -> service.uploadProfileImage(1L, image))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not match");
    }
}
