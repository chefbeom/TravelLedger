package com.playdata.calen.travel.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playdata.calen.account.domain.AppUserRole;
import com.playdata.calen.account.security.AppUserPrincipal;
import com.playdata.calen.travel.service.TravelMediaStorageService;
import com.playdata.calen.travel.service.TravelLoginMapPreviewService;
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
    void loginMapPinThumbnailReturnsOnlyCachedSafeThumbnailBytes() {
        TravelService travelService = mock(TravelService.class);
        TravelMediaStorageService travelMediaStorageService = mock(TravelMediaStorageService.class);
        TravelLoginMapPreviewService travelLoginMapPreviewService = mock(TravelLoginMapPreviewService.class);
        TravelReverseGeocodeService travelReverseGeocodeService = mock(TravelReverseGeocodeService.class);
        TravelController controller = new TravelController(
                travelService,
                travelLoginMapPreviewService,
                travelMediaStorageService,
                travelReverseGeocodeService
        );
        TravelService.MediaDownload download = new TravelService.MediaDownload(
                "travel-media/1/2/photo.jpg",
                "image/jpeg",
                "photo.jpg"
        );
        ByteArrayResource thumbnailBytes = new ByteArrayResource("thumbnail-bytes".getBytes(StandardCharsets.UTF_8));
        when(travelLoginMapPreviewService.getPublicMarkerThumbnail(4)).thenReturn(download);
        when(travelMediaStorageService.loadThumbnail(download.storagePath(), download.contentType(), 320))
                .thenReturn(new TravelMediaStorageService.PreparedThumbnail(thumbnailBytes, "image/jpeg"));

        ResponseEntity<?> response = controller.getLoginMapPreviewMarkerThumbnail(4);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(((ByteArrayResource) response.getBody()).getByteArray())
                .isEqualTo("thumbnail-bytes".getBytes(StandardCharsets.UTF_8));
        verify(travelLoginMapPreviewService).getPublicMarkerThumbnail(4);
        verify(travelMediaStorageService).loadThumbnail(download.storagePath(), download.contentType(), 320);
    }

    @Test
    void loginMapPinThumbnailDoesNotFallBackToOriginalWhenThumbnailIsUnavailable() {
        TravelService travelService = mock(TravelService.class);
        TravelMediaStorageService travelMediaStorageService = mock(TravelMediaStorageService.class);
        TravelLoginMapPreviewService travelLoginMapPreviewService = mock(TravelLoginMapPreviewService.class);
        TravelReverseGeocodeService travelReverseGeocodeService = mock(TravelReverseGeocodeService.class);
        TravelController controller = new TravelController(
                travelService,
                travelLoginMapPreviewService,
                travelMediaStorageService,
                travelReverseGeocodeService
        );
        TravelService.MediaDownload download = new TravelService.MediaDownload(
                "travel-media/1/2/photo.jpg",
                "image/jpeg",
                "photo.jpg"
        );
        when(travelLoginMapPreviewService.getPublicMarkerThumbnail(4)).thenReturn(download);
        when(travelMediaStorageService.loadThumbnail(download.storagePath(), download.contentType(), 320)).thenReturn(null);

        ResponseEntity<?> response = controller.getLoginMapPreviewMarkerThumbnail(4);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(travelMediaStorageService, never()).loadAsResource(download.storagePath());
    }

    @Test
    void shouldReturnThumbnailContentWhenPreparedThumbnailIsMissing() {
        TravelService travelService = mock(TravelService.class);
        TravelMediaStorageService travelMediaStorageService = mock(TravelMediaStorageService.class);
        TravelLoginMapPreviewService travelLoginMapPreviewService = mock(TravelLoginMapPreviewService.class);
        TravelReverseGeocodeService travelReverseGeocodeService = mock(TravelReverseGeocodeService.class);
        TravelController controller = new TravelController(
                travelService,
                travelLoginMapPreviewService,
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
        when(travelMediaStorageService.loadThumbnail(download.storagePath(), download.contentType(), 480))
                .thenReturn(new TravelMediaStorageService.PreparedThumbnail(originalResource, "image/jpeg"));

        ResponseEntity<?> response = controller.downloadMedia(currentUser, 7L, true, 480);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("public, max-age=86400");
        assertThat(response.getBody()).isInstanceOf(ByteArrayResource.class);
        assertThat(((ByteArrayResource) response.getBody()).getByteArray())
                .isEqualTo("image-bytes".getBytes(StandardCharsets.UTF_8));
        verify(travelService).getMediaDownload(1L, 7L);
        verify(travelMediaStorageService).loadThumbnail(download.storagePath(), download.contentType(), 480);
    }
}
