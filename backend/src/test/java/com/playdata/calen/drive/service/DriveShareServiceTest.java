package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.account.service.UserNotificationService;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.media.ImageThumbnailService;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveItemType;
import com.playdata.calen.drive.domain.DriveShare;
import com.playdata.calen.drive.domain.DriveSharePermission;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.drive.repository.DriveShareRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    @Mock
    private UserNotificationService userNotificationService;

    @Mock
    private DriveDownloadLinkAccessLogService driveDownloadLinkAccessLogService;

    @Test
    void shareFilesStoresRequestedViewPermission() {
        AppUser owner = user(1L, "owner");
        AppUser recipient = user(2L, "friend");
        DriveItem sharedFile = file(7L);
        sharedFile.setOwner(owner);

        when(driveService.getOwner(1L)).thenReturn(owner);
        when(appUserRepository.findByLoginId("friend")).thenReturn(Optional.of(recipient));
        when(driveService.getOwnedFile(1L, 7L)).thenReturn(sharedFile);
        when(driveShareRepository.findByItem_IdAndRecipient_Id(7L, 2L)).thenReturn(Optional.empty());
        when(driveShareRepository.save(any(DriveShare.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DriveShareService service = newService();

        DriveDtos.ActionResponse response = service.shareFiles(1L, List.of(7L), "friend", "view");

        ArgumentCaptor<DriveShare> shareCaptor = ArgumentCaptor.forClass(DriveShare.class);
        verify(driveShareRepository).save(shareCaptor.capture());
        assertThat(shareCaptor.getValue().getPermission()).isEqualTo(DriveSharePermission.VIEW);
        assertThat(response.affectedCount()).isEqualTo(1);
    }

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

    @Test
    void receivedSharesDoNotIncludeTrashedSharedFiles() {
        DriveShare activeShare = share(file(7L), user(1L, "owner"), user(2L, "friend"));
        DriveItem trashedFile = file(8L);
        trashedFile.markTrashed();
        DriveShare trashedShare = share(trashedFile, user(1L, "owner"), user(2L, "friend"));

        when(driveShareRepository.findAllByRecipient_IdOrderByCreatedAtDesc(2L))
                .thenReturn(List.of(trashedShare, activeShare));

        DriveShareService service = newService();

        assertThat(service.getReceivedShares(2L))
                .extracting(response -> response.fileId())
                .containsExactly(7L);
    }

    @Test
    void downloadSharedFileRejectsViewOnlyShareWithoutLoadingObject() {
        DriveItem sharedFile = file(7L);
        DriveShare share = share(sharedFile, user(1L, "owner"), user(2L, "friend"));
        share.setPermission(DriveSharePermission.VIEW);

        when(driveShareRepository.findByItem_IdAndRecipient_Id(7L, 2L)).thenReturn(Optional.of(share));

        DriveShareService service = newService();

        assertThatThrownBy(() -> service.downloadSharedFile(2L, 7L))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Shared file permission does not allow download.");

        verify(driveStorageService, never()).loadObjectBytes("drive/locked.txt");
    }

    @Test
    void sharedDownloadUrlRecordsSuccessfulAccess() {
        DriveItem sharedFile = file(7L);
        DriveShare share = share(sharedFile, user(1L, "owner"), user(2L, "friend"));
        share.setId(31L);
        share.setPermission(DriveSharePermission.DOWNLOAD);
        DriveDownloadLinkAccessLogService.AccessMetadata metadata = new DriveDownloadLinkAccessLogService.AccessMetadata("203.0.113.8", "agent");

        when(driveShareRepository.findByItem_IdAndRecipient_Id(7L, 2L)).thenReturn(Optional.of(share));
        when(driveStorageService.generateDownloadUrl("drive/locked.txt", "locked.txt", "text/plain"))
                .thenReturn("https://download.example/file");

        DriveShareService service = newService();

        String downloadUrl = service.getSharedFileDownloadUrl(2L, 7L, metadata);

        assertThat(downloadUrl).isEqualTo("https://download.example/file");
        verify(driveDownloadLinkAccessLogService).recordDirectShareAccess(31L, 7L, 1L, 2L, "success", metadata);
    }

    @Test
    void sharedDownloadUrlRecordsViewOnlyDeniedAccess() {
        DriveItem sharedFile = file(7L);
        DriveShare share = share(sharedFile, user(1L, "owner"), user(2L, "friend"));
        share.setId(31L);
        share.setPermission(DriveSharePermission.VIEW);
        DriveDownloadLinkAccessLogService.AccessMetadata metadata = new DriveDownloadLinkAccessLogService.AccessMetadata("203.0.113.9", "agent");

        when(driveShareRepository.findByItem_IdAndRecipient_Id(7L, 2L)).thenReturn(Optional.of(share));

        DriveShareService service = newService();

        assertThatThrownBy(() -> service.getSharedFileDownloadUrl(2L, 7L, metadata))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Shared file permission does not allow download.");

        verify(driveDownloadLinkAccessLogService).recordDirectShareAccess(31L, 7L, 1L, 2L, "permission_denied", metadata);
        verify(driveStorageService, never()).generateDownloadUrl(any(), any(), any());
    }

    @Test
    void downloadSharedFileRejectsTrashedSourceWithoutLoadingObject() {
        DriveItem trashedFile = file(7L);
        trashedFile.markTrashed();
        DriveShare share = share(trashedFile, user(1L, "owner"), user(2L, "friend"));

        when(driveShareRepository.findByItem_IdAndRecipient_Id(7L, 2L)).thenReturn(Optional.of(share));

        DriveShareService service = newService();

        assertThatThrownBy(() -> service.downloadSharedFile(2L, 7L))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Shared file is no longer available.");

        verify(driveStorageService, never()).loadObjectBytes("drive/locked.txt");
    }

    @Test
    void saveSharedFileRejectsTrashedSourceWithoutCopyingObject() {
        DriveItem trashedFile = file(7L);
        trashedFile.markTrashed();
        DriveShare share = share(trashedFile, user(1L, "owner"), user(2L, "friend"));

        when(driveShareRepository.findByItem_IdAndRecipient_Id(7L, 2L)).thenReturn(Optional.of(share));

        DriveShareService service = newService();

        assertThatThrownBy(() -> service.saveSharedFileToDrive(2L, 7L, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Shared file is no longer available.");

        verify(driveStorageService, never()).copyObject("drive/locked.txt", "drive/2/locked.txt");
    }

    private DriveShareService newService() {
        return new DriveShareService(
                driveShareRepository,
                driveItemRepository,
                appUserRepository,
                driveStorageService,
                driveService,
                imageThumbnailService,
                userNotificationService,
                driveDownloadLinkAccessLogService
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

    private DriveShare share(DriveItem item, AppUser owner, AppUser recipient) {
        item.setOwner(owner);
        DriveShare share = new DriveShare();
        share.setItem(item);
        share.setOwner(owner);
        share.setRecipient(recipient);
        return share;
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
