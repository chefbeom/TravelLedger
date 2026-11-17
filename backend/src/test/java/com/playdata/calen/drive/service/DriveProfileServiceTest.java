package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.drive.domain.DriveProfileSettings;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.drive.repository.DriveProfileSettingsRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class DriveProfileServiceTest {

    private final DriveProfileService service = new DriveProfileService(
            mock(AppUserRepository.class),
            mock(DriveProfileSettingsRepository.class),
            mock(DriveItemRepository.class),
            mock(DriveStorageService.class),
            mock(DriveShareParticipantFence.class)
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

    @Test
    void successfulProfileUploadRecordsTheActualConfiguredBucket() throws Exception {
        var users = mock(AppUserRepository.class);
        var settingsRepository = mock(DriveProfileSettingsRepository.class);
        var items = mock(DriveItemRepository.class);
        var storage = mock(DriveStorageService.class);
        var fence = mock(DriveShareParticipantFence.class);
        var owner = new AppUser(); owner.setId(1L); owner.setLoginId("owner"); owner.setDisplayName("Owner");
        var settings = new DriveProfileSettings(); settings.setUser(owner); settings.setDisplayName("Owner");
        when(users.findById(1L)).thenReturn(Optional.of(owner));
        when(settingsRepository.findByUser_Id(1L)).thenReturn(Optional.of(settings));
        when(storage.buildProfileImageObjectKey(1L)).thenReturn("drive-profile/1/profile.png");
        when(storage.resolveBucket()).thenReturn("profile-bucket");
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
        var image = new MockMultipartFile("image", "profile.png", "image/png", output.toByteArray());
        new DriveProfileService(users, settingsRepository, items, storage, fence).uploadProfileImage(1L, image);
        assertThat(settings.getProfileImagePath()).isEqualTo("drive-profile/1/profile.png");
        assertThat(settings.getProfileImageBucket()).isEqualTo("profile-bucket");
    }
}
