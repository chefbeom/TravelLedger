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
    private com.playdata.calen.travel.application.port.in.TravelMapLinksUseCase mapLinks;
    private TravelLoginMapPreviewService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        mapLinks = mock(com.playdata.calen.travel.application.port.in.TravelMapLinksUseCase.class);
        service = new TravelLoginMapPreviewService(jdbcTemplate, mapLinks);
    }

    @Test
    void disabledPreviewReturnsNoDataAndDoesNotLoadShare() {
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "shareToken", "public-share-token-123456");

        TravelLoginMapPreviewResponse response = service.getPublicPreview();

        assertFalse(response.enabled());
        assertTrue(response.markers().isEmpty());
        assertTrue(response.routes().isEmpty());
        verify(mapLinks, never()).preview(any());
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
        when(mapLinks.preview("public-share-token-123456")).thenReturn(value(safePreview));

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
        verify(mapLinks).preview(eq("public-share-token-123456"));
    }

    @Test
    void revokedShareFailsClosedWithoutReturningPrivateDetails() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "shareToken", "revoked-share-token-123456");
        when(mapLinks.preview("revoked-share-token-123456"))
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
        when(mapLinks.preview(token)).thenReturn(
                value(new TravelLoginMapPreviewResponse(true, "여행 기록 미리보기", 1, 0,
                        List.of(new TravelLoginMapPreviewMarkerResponse(1, BigDecimal.ONE, BigDecimal.ONE, null)),
                        List.of()))
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
        com.playdata.calen.travel.domain.model.TravelMediaData.Download expected = new com.playdata.calen.travel.domain.model.TravelMediaData.Download("object/photo.jpg", "image/jpeg", "photo.jpg");
        when(mapLinks.previewMedia(token, 2)).thenReturn(expected);

        assertEquals(expected, service.getPublicMarkerThumbnail(2));
        verify(mapLinks).previewMedia(token, 2);
    }

    @Test
    void markerThumbnailIsUnavailableWhenPreviewIsDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "shareToken", "public-share-token-123456");

        assertThrows(NotFoundException.class, () -> service.getPublicMarkerThumbnail(2));
        verifyNoInteractions(mapLinks);
    }

    private static com.playdata.calen.travel.domain.model.TravelMapLinkData.Preview value(TravelLoginMapPreviewResponse v) {
        return new com.playdata.calen.travel.domain.model.TravelMapLinkData.Preview(v.enabled(),
                v.markers().stream().map(m -> new com.playdata.calen.travel.domain.model.TravelMapLinkData.PreviewMarker(m.number(), m.latitude(), m.longitude(), m.thumbnailUrl() != null)).toList(),
                v.routes().stream().map(r -> new com.playdata.calen.travel.domain.model.TravelMapLinkData.PreviewRoute(r.number(),
                        r.points().stream().map(p -> new com.playdata.calen.travel.domain.model.TravelMapLinkData.PreviewPoint(p.latitude(), p.longitude())).toList())).toList());
    }
}
