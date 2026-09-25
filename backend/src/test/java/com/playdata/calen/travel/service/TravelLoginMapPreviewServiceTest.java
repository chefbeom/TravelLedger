package com.playdata.calen.travel.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.playdata.calen.travel.dto.TravelLoginMapPreviewResponse;
import com.playdata.calen.travel.dto.TravelLoginMapPreviewMarkerResponse;
import com.playdata.calen.travel.dto.TravelLoginMapPreviewPointResponse;
import com.playdata.calen.travel.dto.TravelLoginMapPreviewRouteResponse;
import com.playdata.calen.common.exception.NotFoundException;
import java.math.BigDecimal;
import java.util.List;
import com.playdata.calen.common.exception.BadRequestException;
import com.playdata.calen.travel.dto.AdminLoginMapPreviewUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class TravelLoginMapPreviewServiceTest {

    private JdbcTemplate jdbcTemplate;
    private TravelService travelService;
    private TravelLoginMapPreviewService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        travelService = mock(TravelService.class);
        service = new TravelLoginMapPreviewService(jdbcTemplate, travelService);
    }

    @Test
    void disabledPreviewReturnsNoDataAndDoesNotLoadShare() {
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "shareToken", "public-share-token-123456");

        TravelLoginMapPreviewResponse response = service.getPublicPreview();

        assertFalse(response.enabled());
        assertTrue(response.markers().isEmpty());
        assertTrue(response.routes().isEmpty());
        verify(travelService, never()).getTravelMapShareLoginPreview(any());
    }

    @Test
    void publicPreviewRoundsCoordinatesAndDoesNotReturnShareMetadata() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "shareToken", "public-share-token-123456");

        TravelLoginMapPreviewResponse safePreview = new TravelLoginMapPreviewResponse(
                true,
                "여행 기록 미리보기",
                1,
                1,
                List.of(new TravelLoginMapPreviewMarkerResponse(
                        1,
                        new BigDecimal("35.123"),
                        new BigDecimal("129.988"),
                        "/api/travel/public/login-map-preview/markers/1/thumbnail"
                )),
                List.of(new TravelLoginMapPreviewRouteResponse(1, List.of(
                        new TravelLoginMapPreviewPointResponse(new BigDecimal("35.1"), new BigDecimal("129.9")),
                        new TravelLoginMapPreviewPointResponse(new BigDecimal("35.2"), new BigDecimal("129.8"))
                )))
        );
        when(travelService.getTravelMapShareLoginPreview("public-share-token-123456")).thenReturn(safePreview);

        TravelLoginMapPreviewResponse response = service.getPublicPreview();

        assertTrue(response.enabled());
        assertEquals("여행 기록 미리보기", response.title());
        assertEquals(1, response.markerCount());
        assertEquals(new BigDecimal("35.123"), response.markers().get(0).latitude());
        assertEquals(new BigDecimal("129.988"), response.markers().get(0).longitude());
        assertEquals("/api/travel/public/login-map-preview/markers/1/thumbnail", response.markers().get(0).thumbnailUrl());
        org.junit.jupiter.api.Assertions.assertFalse(response.markers().get(0).thumbnailUrl().contains("public-share-token"));
        assertEquals(1, response.routeCount());
        assertEquals(2, response.routes().get(0).points().size());
        assertEquals(new BigDecimal("35.1"), response.routes().get(0).points().get(0).latitude());
        verify(travelService).getTravelMapShareLoginPreview(eq("public-share-token-123456"));
    }

    @Test
    void revokedShareFailsClosedWithoutReturningPrivateDetails() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "shareToken", "revoked-share-token-123456");
        when(travelService.getTravelMapShareLoginPreview("revoked-share-token-123456"))
                .thenThrow(new IllegalStateException("share unavailable"));

        TravelLoginMapPreviewResponse response = service.getPublicPreview();

        assertFalse(response.enabled());
        assertEquals(0, response.markerCount());
        assertEquals(0, response.routeCount());
        assertTrue(response.markers().isEmpty());
        assertTrue(response.routes().isEmpty());
    }

    @Test
    void enablingPreviewRequiresAConfiguredShareLink() {
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "shareToken", null);

        org.junit.jupiter.api.Assertions.assertThrows(
                BadRequestException.class,
                () -> service.updateAdminSettings(new AdminLoginMapPreviewUpdateRequest(true, "", false))
        );
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void adminStatusNeverReturnsTheStoredShareToken() {
        String token = "public-share-token-123456";
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "shareToken", token);

        var status = service.getAdminSettings();

        assertTrue(status.configured());
        assertFalse(status.enabled());
        assertFalse(status.toString().contains(token));
    }

    @Test
    void acceptsAnExistingPublicShareUrlWithoutReturningItsToken() {
        String token = "public-share-token-123456";
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "shareToken", null);
        when(travelService.getTravelMapShareLoginPreview(token)).thenReturn(
                new TravelLoginMapPreviewResponse(true, "여행 기록 미리보기", 1, 0,
                        List.of(new TravelLoginMapPreviewMarkerResponse(1, BigDecimal.ONE, BigDecimal.ONE, null)),
                        List.of())
        );

        var status = service.updateAdminSettings(new AdminLoginMapPreviewUpdateRequest(
                true,
                "https://travel.example/#travel-share/" + token,
                false
        ));

        assertTrue(status.enabled());
        assertTrue(status.configured());
        assertFalse(status.toString().contains(token));
    }

    @Test
    void markerThumbnailRequiresEnabledPreviewAndDelegatesUsingStoredShareToken() {
        String token = "public-share-token-123456";
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "shareToken", token);
        TravelService.MediaDownload expected = new TravelService.MediaDownload("object/photo.jpg", "image/jpeg", "photo.jpg");
        when(travelService.getTravelMapShareLoginPreviewMarkerMediaDownload(token, 2)).thenReturn(expected);

        assertEquals(expected, service.getPublicMarkerThumbnail(2));
        verify(travelService).getTravelMapShareLoginPreviewMarkerMediaDownload(token, 2);
    }

    @Test
    void markerThumbnailIsUnavailableWhenPreviewIsDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "shareToken", "public-share-token-123456");

        assertThrows(NotFoundException.class, () -> service.getPublicMarkerThumbnail(2));
        verifyNoInteractions(travelService);
    }
}
