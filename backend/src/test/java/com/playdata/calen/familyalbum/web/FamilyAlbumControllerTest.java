package com.playdata.calen.familyalbum.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.familyalbum.service.FamilyAlbumService;
import com.playdata.calen.familyalbum.service.FamilyMediaStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class FamilyAlbumControllerTest {

    @Test
    void shouldNotFallbackToOriginalImageWhenThumbnailIsUnavailable() {
        FamilyAlbumService familyAlbumService = mock(FamilyAlbumService.class);
        FamilyMediaStorageService familyMediaStorageService = mock(FamilyMediaStorageService.class);
        FamilyAlbumController controller = new FamilyAlbumController(familyAlbumService, familyMediaStorageService);
        AppUserPrincipal currentUser = new AppUserPrincipal(
                1L,
                "tester",
                "Tester",
                "password",
                AppUserRole.USER,
                true
        );
        FamilyAlbumService.MediaDownload download = new FamilyAlbumService.MediaDownload(
                "family-media/1/2/photo.jpg",
                mock(org.springframework.core.io.Resource.class),
                "photo.jpg",
                "image/jpeg"
        );

        when(familyAlbumService.getMediaDownload(1L, 7L)).thenReturn(download);
        when(familyMediaStorageService.loadThumbnail(download.storagePath(), download.contentType(), 480))
                .thenReturn(null);

        ResponseEntity<?> response = controller.downloadMedia(currentUser, 7L, true, 480);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(familyAlbumService).getMediaDownload(1L, 7L);
        verify(familyMediaStorageService).loadThumbnail(download.storagePath(), download.contentType(), 480);
    }
}
