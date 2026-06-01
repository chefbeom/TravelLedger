package com.playdata.calen.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUser;
import com.playdata.calen.account.repository.AppUserRepository;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.common.media.ImageThumbnailService;
import com.playdata.calen.drive.domain.DriveItem;
import com.playdata.calen.drive.domain.DriveItemType;
import com.playdata.calen.drive.dto.DriveDtos;
import com.playdata.calen.drive.repository.DriveDownloadLinkRepository;
import com.playdata.calen.drive.repository.DriveItemRepository;
import com.playdata.calen.drive.repository.DriveShareRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DriveServiceTest {

    @Mock
    private DriveItemRepository driveItemRepository;

    @Mock
    private DriveShareRepository driveShareRepository;

    @Mock
    private DriveDownloadLinkRepository driveDownloadLinkRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private DriveStorageService driveStorageService;

    @Mock
    private ImageThumbnailService imageThumbnailService;

    @Test
    void listPageKeepsFoldersBeforeFilesWhenSortedByRecent() {
        AppUser owner = owner();
        DriveItem oldFolder = item(2L, owner, DriveItemType.FOLDER, "Project Folder", 0L, 10);
        DriveItem newerFile = item(1L, owner, DriveItemType.FILE, "z-latest.pdf", 200L, 20);

        when(appUserRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(driveItemRepository.findAllByOwner_Id(1L)).thenReturn(List.of(newerFile, oldFolder));

        DriveService service = newService();

        DriveDtos.FileListPageResponse response = service.listPage(
                1L,
                DriveDtos.ListPageRequest.builder()
                        .page(0)
                        .size(20)
                        .sortOption("recent")
                        .build()
        );

        assertThat(response.fileList())
                .extracting(DriveDtos.FileItemResponse::nodeType)
                .containsExactly("FOLDER", "FILE");
        assertThat(response.fileList())
                .extracting(DriveDtos.FileItemResponse::fileOriginName)
                .containsExactly("Project Folder", "z-latest.pdf");
    }

    @Test
    void listPageSortsFoldersByNameBeforeFilesByDefault() {
        AppUser owner = owner();
        DriveItem file = item(1L, owner, DriveItemType.FILE, "alpha.txt", 100L, 10);
        DriveItem zFolder = item(2L, owner, DriveItemType.FOLDER, "Zeta", 0L, 10);
        DriveItem aFolder = item(3L, owner, DriveItemType.FOLDER, "Archive", 0L, 10);

        when(appUserRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(driveItemRepository.findAllByOwner_Id(1L)).thenReturn(List.of(file, zFolder, aFolder));

        DriveService service = newService();

        DriveDtos.FileListPageResponse response = service.listPage(
                1L,
                DriveDtos.ListPageRequest.builder()
                        .page(0)
                        .size(20)
                        .build()
        );

        assertThat(response.fileList())
                .extracting(DriveDtos.FileItemResponse::fileOriginName)
                .containsExactly("Archive", "Zeta", "alpha.txt");
    }

    @Test
    void listPageSearchesAcrossDriveAndReturnsFolderPath() {
        AppUser owner = owner();
        DriveItem tripsFolder = item(2L, owner, DriveItemType.FOLDER, "Trips", 0L, 10);
        DriveItem nestedFile = item(3L, owner, DriveItemType.FILE, "tokyo-ticket.pdf", 100L, 20);
        nestedFile.setParent(tripsFolder);

        when(appUserRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(driveItemRepository.findAllByOwner_Id(1L)).thenReturn(List.of(tripsFolder, nestedFile));

        DriveService service = newService();

        DriveDtos.FileListPageResponse response = service.listPage(
                1L,
                DriveDtos.ListPageRequest.builder()
                        .page(0)
                        .size(20)
                        .searchQuery("ticket")
                        .build()
        );

        assertThat(response.breadcrumbs()).isEmpty();
        assertThat(response.fileList())
                .extracting(DriveDtos.FileItemResponse::fileOriginName)
                .containsExactly("tokyo-ticket.pdf");
        assertThat(response.fileList().get(0).folderPath()).isEqualTo("내 드라이브 / Trips");
    }

    @Test
    void renameRejectsFileInsideLockedFolder() {
        AppUser owner = owner();
        DriveItem lockedFolder = item(2L, owner, DriveItemType.FOLDER, "Private", 0L, 10);
        lockedFolder.setLockedFile(true);
        DriveItem file = item(3L, owner, DriveItemType.FILE, "note.txt", 100L, 20);
        file.setParent(lockedFolder);

        when(appUserRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(driveItemRepository.findByIdAndOwner_Id(3L, 1L)).thenReturn(Optional.of(file));

        DriveService service = newService();

        assertThatThrownBy(() -> service.renameItem(1L, 3L, "renamed.txt"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Locked drive items cannot be changed. Unlock the item first.");
        assertThat(file.getOriginalName()).isEqualTo("note.txt");
    }

    @Test
    void moveToTrashRejectsFileInsideLockedFolder() {
        AppUser owner = owner();
        DriveItem lockedFolder = item(2L, owner, DriveItemType.FOLDER, "Private", 0L, 10);
        lockedFolder.setLockedFile(true);
        DriveItem file = item(3L, owner, DriveItemType.FILE, "note.txt", 100L, 20);
        file.setParent(lockedFolder);

        when(appUserRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(driveItemRepository.findByIdAndOwner_Id(3L, 1L)).thenReturn(Optional.of(file));

        DriveService service = newService();

        assertThatThrownBy(() -> service.moveToTrash(1L, 3L))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Locked drive items cannot be changed. Unlock the item first.");
        assertThat(file.isTrashed()).isFalse();
    }

    @Test
    void downloadItemsAsZipKeepsFolderPaths() throws Exception {
        AppUser owner = owner();
        DriveItem folder = item(2L, owner, DriveItemType.FOLDER, "Trips", 0L, 10);
        DriveItem nestedFile = item(3L, owner, DriveItemType.FILE, "tokyo.txt", 100L, 20);
        nestedFile.setParent(folder);
        DriveItem rootFile = item(4L, owner, DriveItemType.FILE, "root.txt", 50L, 30);

        when(appUserRepository.findById(1L)).thenReturn(Optional.of(owner));
        when(driveItemRepository.findByIdAndOwner_Id(2L, 1L)).thenReturn(Optional.of(folder));
        when(driveItemRepository.findByIdAndOwner_Id(4L, 1L)).thenReturn(Optional.of(rootFile));
        when(driveItemRepository.findAllByOwner_Id(1L)).thenReturn(List.of(folder, nestedFile, rootFile));
        when(driveStorageService.loadObjectBytes("drive/tokyo.txt")).thenReturn("nested".getBytes(StandardCharsets.UTF_8));
        when(driveStorageService.loadObjectBytes("drive/root.txt")).thenReturn("root".getBytes(StandardCharsets.UTF_8));

        DriveService service = newService();

        DriveService.DriveFilePayload payload = service.downloadItemsAsZip(1L, List.of(2L, 4L));

        assertThat(payload.contentType()).isEqualTo("application/zip");
        assertThat(payload.fileName()).isEqualTo("calendrive-selection.zip");
        assertThat(readZipEntries(payload.bytes()))
                .containsEntry("Trips/tokyo.txt", "nested")
                .containsEntry("root.txt", "root");
    }

    private DriveService newService() {
        return new DriveService(
                driveItemRepository,
                driveShareRepository,
                driveDownloadLinkRepository,
                appUserRepository,
                driveStorageService,
                imageThumbnailService
        );
    }

    private AppUser owner() {
        AppUser owner = new AppUser();
        owner.setId(1L);
        owner.setLoginId("drive-user");
        owner.setDisplayName("Drive User");
        owner.setActive(true);
        return owner;
    }

    private DriveItem item(Long id, AppUser owner, DriveItemType type, String name, long size, int modifiedOffsetMinutes) {
        LocalDateTime baseTime = LocalDateTime.of(2026, 6, 1, 12, 0);
        DriveItem item = new DriveItem();
        item.setId(id);
        item.setOwner(owner);
        item.setItemType(type);
        item.setOriginalName(name);
        item.setStoredName(name);
        item.setExtension(type == DriveItemType.FILE ? name.substring(name.lastIndexOf('.') + 1) : "");
        item.setStoragePath(type == DriveItemType.FILE ? "drive/" + name : null);
        item.setFileSize(size);
        item.setUploadedAt(baseTime.plusMinutes(modifiedOffsetMinutes));
        item.setLastModifiedAt(baseTime.plusMinutes(modifiedOffsetMinutes));
        return item;
    }

    private Map<String, String> readZipEntries(byte[] bytes) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return entries;
    }
}
