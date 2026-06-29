package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.exception.NotFoundException;
import com.playdata.calen.drive.domain.DriveDownloadLink;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveItemType;
import com.playdata.calen.drive.repository.DriveDownloadLinkRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DriveDownloadLinkServiceTest {

    @Mock
    private DriveDownloadLinkRepository driveDownloadLinkRepository;

    @Mock
    private DriveService driveService;

    @Mock
    private DriveStorageService driveStorageService;

    @Mock
    private DriveDownloadLinkAccessLogService driveDownloadLinkAccessLogService;

    @Test
    void downloadByTokenRecordsLinkAccessTimeWhenAvailable() {
        DriveDownloadLink link = activeLink();

        when(driveDownloadLinkRepository.findByToken("public-token")).thenReturn(Optional.of(link));
        when(driveStorageService.loadObjectBytes("drive/file.txt")).thenReturn(new byte[] {1, 2, 3});

        DriveDownloadLinkService service = newService();

        service.downloadByToken("public-token");

        assertThat(link.getDownloadCount()).isEqualTo(1);
        assertThat(link.getLastAccessedAt()).isNotNull();
        assertThat(link.getItem().getLastAccessedAt()).isNotNull();
        verify(driveDownloadLinkAccessLogService).record(eq(7L), eq(11L), eq(1L), eq("public-token"), eq("success"), isNull());
    }

    @Test
    void resolveDownloadUrlByTokenRecordsAccessWithoutLoadingFileBytes() {
        DriveDownloadLink link = activeLink();

        when(driveDownloadLinkRepository.findByToken("public-token")).thenReturn(Optional.of(link));
        when(driveService.resolveContentType("txt")).thenReturn("text/plain");
        when(driveStorageService.generateDownloadUrl("drive/file.txt", "file.txt", "text/plain"))
                .thenReturn("https://storage.example/download");

        DriveDownloadLinkService service = newService();

        String downloadUrl = service.resolveDownloadUrlByToken("public-token");

        assertThat(downloadUrl).isEqualTo("https://storage.example/download");
        assertThat(link.getDownloadCount()).isEqualTo(1);
        assertThat(link.getLastAccessedAt()).isNotNull();
        assertThat(link.getItem().getLastAccessedAt()).isNotNull();
        verify(driveStorageService, never()).loadObjectBytes(anyString());
        verify(driveDownloadLinkAccessLogService).record(eq(7L), eq(11L), eq(1L), eq("public-token"), eq("success"), isNull());
    }
    @Test
    void downloadByTokenRejectsBlankTokenWithoutLoadingFile() {
        DriveDownloadLinkService service = newService();

        assertThatThrownBy(() -> service.downloadByToken("  "))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Download link was not found.");

        verify(driveDownloadLinkRepository, never()).findByToken(anyString());
        verify(driveStorageService, never()).loadObjectBytes(anyString());
        verify(driveDownloadLinkAccessLogService).record(isNull(), isNull(), isNull(), eq("  "), eq("invalid"), isNull());
    }

    @Test
    void downloadByTokenRejectsExpiredLinkWithoutLoadingFile() {
        DriveDownloadLink link = activeLink();
        link.setExpiresAt(LocalDateTime.now().minusMinutes(1));

        when(driveDownloadLinkRepository.findByToken("public-token")).thenReturn(Optional.of(link));

        DriveDownloadLinkService service = newService();

        assertThatThrownBy(() -> service.downloadByToken("public-token"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Download link is expired or no longer available.");

        assertThat(link.getDownloadCount()).isZero();
        verify(driveStorageService, never()).loadObjectBytes(anyString());
    }

    @Test
    void downloadByTokenRejectsFolderTargetWithoutLoadingFile() {
        DriveDownloadLink link = activeLink();
        link.getItem().setItemType(DriveItemType.FOLDER);

        when(driveDownloadLinkRepository.findByToken("public-token")).thenReturn(Optional.of(link));

        DriveDownloadLinkService service = newService();

        assertThatThrownBy(() -> service.downloadByToken("public-token"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Download link is expired or no longer available.");

        assertThat(link.getDownloadCount()).isZero();
        verify(driveStorageService, never()).loadObjectBytes(anyString());
    }

    @Test
    void downloadByTokenRejectsMissingStoragePathWithoutCountingAccess() {
        DriveDownloadLink link = activeLink();
        link.getItem().setStoragePath(" ");

        when(driveDownloadLinkRepository.findByToken("public-token")).thenReturn(Optional.of(link));

        DriveDownloadLinkService service = newService();

        assertThatThrownBy(() -> service.downloadByToken("public-token"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Download link is expired or no longer available.");

        assertThat(link.getDownloadCount()).isZero();
        assertThat(link.getLastAccessedAt()).isNull();
        assertThat(link.getItem().getLastAccessedAt()).isNull();
        verify(driveStorageService, never()).loadObjectBytes(anyString());
    }

    @Test
    void downloadByTokenRejectsRevokedLinkWithoutLoadingFile() {
        DriveDownloadLink link = activeLink();
        link.setRevokedAt(LocalDateTime.now().minusMinutes(1));

        when(driveDownloadLinkRepository.findByToken("public-token")).thenReturn(Optional.of(link));

        DriveDownloadLinkService service = newService();

        assertThatThrownBy(() -> service.downloadByToken("public-token"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Download link is expired or no longer available.");

        assertThat(link.getDownloadCount()).isZero();
        verify(driveStorageService, never()).loadObjectBytes("drive/file.txt");
        verify(driveDownloadLinkAccessLogService).record(eq(7L), eq(11L), eq(1L), eq("public-token"), eq("revoked"), isNull());
    }

    @Test
    void downloadByTokenRejectsMissingLinkWithoutLoadingFile() {
        when(driveDownloadLinkRepository.findByToken("missing-token")).thenReturn(Optional.empty());

        DriveDownloadLinkService service = newService();

        assertThatThrownBy(() -> service.downloadByToken("missing-token"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Download link was not found.");

        verify(driveStorageService, never()).loadObjectBytes(anyString());
        verify(driveDownloadLinkAccessLogService).record(isNull(), isNull(), isNull(), eq("missing-token"), eq("invalid"), isNull());
    }

    @Test
    void downloadByTokenRejectsTrashedFileWithoutLoadingFile() {
        DriveDownloadLink link = activeLink();
        link.getItem().setTrashed(true);

        when(driveDownloadLinkRepository.findByToken("public-token")).thenReturn(Optional.of(link));

        DriveDownloadLinkService service = newService();

        assertThatThrownBy(() -> service.downloadByToken("public-token"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Download link is expired or no longer available.");

        assertThat(link.getDownloadCount()).isZero();
        verify(driveStorageService, never()).loadObjectBytes(anyString());
    }

    @Test
    void downloadByTokenRejectsDownloadLimitWithoutLoadingFile() {
        DriveDownloadLink link = activeLink();
        link.setDownloadCount(3);

        when(driveDownloadLinkRepository.findByToken("public-token")).thenReturn(Optional.of(link));

        DriveDownloadLinkService service = newService();

        assertThatThrownBy(() -> service.downloadByToken("public-token"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Download link is expired or no longer available.");

        assertThat(link.getDownloadCount()).isEqualTo(3);
        verify(driveStorageService, never()).loadObjectBytes(anyString());
    }

    @Test
    void listAccessLogsRejectsNonOwnerBeforeReadingLogs() {
        when(driveDownloadLinkRepository.findByIdAndOwner_Id(7L, 2L)).thenReturn(Optional.empty());

        DriveDownloadLinkService service = newService();

        assertThatThrownBy(() -> service.listAccessLogs(2L, 7L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Download link was not found.");

        verify(driveDownloadLinkAccessLogService, never()).listRecentLogs(2L, 7L);
    }
    @Test
    void revokeLinkReturnsUnavailableLink() {
        DriveDownloadLink link = activeLink();

        when(driveDownloadLinkRepository.findByIdAndOwner_Id(7L, 1L)).thenReturn(Optional.of(link));

        DriveDownloadLinkService service = newService();

        var response = service.revokeLink(1L, 7L);

        assertThat(response.revokedAt()).isNotNull();
        assertThat(response.available()).isFalse();
    }

    @Test
    void revokeLinkRejectsLockedFileWithoutRevokingLink() {
        DriveDownloadLink link = activeLink();
        doThrow(new BadRequestException("Locked drive items cannot be changed. Unlock the item first."))
                .when(driveService)
                .ensureUnlocked(link.getItem());

        when(driveDownloadLinkRepository.findByIdAndOwner_Id(7L, 1L)).thenReturn(Optional.of(link));

        DriveDownloadLinkService service = newService();

        assertThatThrownBy(() -> service.revokeLink(1L, 7L))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Locked drive items cannot be changed. Unlock the item first.");

        assertThat(link.getRevokedAt()).isNull();
    }

    private DriveDownloadLinkService newService() {
        return new DriveDownloadLinkService(
                driveDownloadLinkRepository,
                driveService,
                driveStorageService,
                driveDownloadLinkAccessLogService
        );
    }

    private DriveDownloadLink activeLink() {
        AppUser owner = new AppUser();
        owner.setId(1L);

        DriveItem item = new DriveItem();
        item.setId(11L);
        item.setOwner(owner);
        item.setItemType(DriveItemType.FILE);
        item.setOriginalName("file.txt");
        item.setExtension("txt");
        item.setStoragePath("drive/file.txt");
        item.setFileSize(12L);

        DriveDownloadLink link = new DriveDownloadLink();
        link.setId(7L);
        link.setOwner(owner);
        link.setItem(item);
        link.setToken("public-token");
        link.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        link.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        link.setMaxDownloads(3);
        link.setDownloadCount(0);
        return link;
    }
}
