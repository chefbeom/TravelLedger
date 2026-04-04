package com.playdata.calen.travel.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import com.playdata.calen.travel.service.TravelReverseGeocodeService;
import com.playdata.calen.travel.service.TravelService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class TravelControllerTest {

    @Test
    void shouldStreamOriginalMediaWhenPreparedThumbnailIsMissing() {
        TravelService travelService = mock(TravelService.class);
        TravelMediaStorageService travelMediaStorageService = mock(TravelMediaStorageService.class);
        TravelReverseGeocodeService travelReverseGeocodeService = mock(TravelReverseGeocodeService.class);
        TravelController controller = new TravelController(
                travelService,
                travelMediaStorageService,
                travelReverseGeocodeService
        );
        AppUserPrincipal currentUser = new AppUserPrincipal(
                1L,
                "tester",
                "Tester",
                "password",
                AppUserRole.USER,
                true
        );
        TravelService.MediaDownload download = new TravelService.MediaDownload(
                "travel-media/1/2/photo.jpg",
                "image/jpeg",
                "photo.jpg"
        );
        ByteArrayResource originalResource = new ByteArrayResource("image-bytes".getBytes(StandardCharsets.UTF_8));

        when(travelService.getMediaDownload(1L, 7L)).thenReturn(download);
        when(travelMediaStorageService.loadPreparedThumbnail(download.storagePath(), download.contentType(), 480))
                .thenReturn(null);
        when(travelMediaStorageService.loadAsResource(download.storagePath())).thenReturn(originalResource);

        ResponseEntity<?> response = controller.downloadMedia(currentUser, 7L, true, 480);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("public, max-age=3600");
        assertThat(response.getBody()).isSameAs(originalResource);
        verify(travelService).getMediaDownload(1L, 7L);
        verify(travelMediaStorageService).loadPreparedThumbnail(download.storagePath(), download.contentType(), 480);
        verify(travelMediaStorageService).loadAsResource(download.storagePath());
    }
}
