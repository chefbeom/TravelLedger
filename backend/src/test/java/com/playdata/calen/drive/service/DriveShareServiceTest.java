package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.media.ImageThumbnailService;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveItemType;
import com.playdata.calen.drive.domain.DriveShare;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.drive.repository.DriveShareRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DriveShareServiceTest {

    @Mock
    private DriveShareRepository driveShareRepository;

    @Mock
    private DriveItemRepository driveItemRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private DriveStorageService driveStorageService;

    @Mock
    private DriveService driveService;

    @Mock
    private ImageThumbnailService imageThumbnailService;

    @Test
    void cancelShareRejectsLockedFileBeforeChangingRecipients() {
        AppUser recipient = user(2L, "friend");
        DriveItem lockedFile = file(7L);
        lockedFile.setLockedFile(true);

        when(appUserRepository.findByLoginId("friend")).thenReturn(Optional.of(recipient));
        when(driveService.getOwnedFile(1L, 7L)).thenReturn(lockedFile);
        doThrow(new BadRequestException("Locked drive items cannot be changed. Unlock the item first."))
                .when(driveService)
                .ensureUnlocked(lockedFile);

        DriveShareService service = newService();

        assertThatThrownBy(() -> service.cancelShare(1L, List.of(7L), "friend"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Locked drive items cannot be changed. Unlock the item first.");

        verify(driveShareRepository, never()).deleteByItem_IdAndRecipient_Id(7L, 2L);
    }

    @Test
    void cancelAllSharesRejectsLockedFileBeforeDeletingShares() {
        DriveItem lockedFile = file(7L);
        lockedFile.setLockedFile(true);

        when(driveService.getOwnedFile(1L, 7L)).thenReturn(lockedFile);
        doThrow(new BadRequestException("Locked drive items cannot be changed. Unlock the item first."))
                .when(driveService)
                .ensureUnlocked(lockedFile);

        DriveShareService service = newService();

        assertThatThrownBy(() -> service.cancelAllShares(1L, List.of(7L)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Locked drive items cannot be changed. Unlock the item first.");

        verify(driveShareRepository, never()).deleteAll(org.mockito.ArgumentMatchers.<List<DriveShare>>any());
    }

    private DriveShareService newService() {
        return new DriveShareService(
                driveShareRepository,
                driveItemRepository,
                appUserRepository,
                driveStorageService,
                driveService,
                imageThumbnailService
        );
    }

    private AppUser user(Long id, String loginId) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setLoginId(loginId);
        user.setDisplayName(loginId);
        user.setActive(true);
        return user;
    }

    private DriveItem file(Long id) {
        DriveItem file = new DriveItem();
        file.setId(id);
        file.setItemType(DriveItemType.FILE);
        file.setOriginalName("locked.txt");
        file.setStoredName("locked.txt");
        file.setExtension("txt");
        file.setStoragePath("drive/locked.txt");
        return file;
    }
}
