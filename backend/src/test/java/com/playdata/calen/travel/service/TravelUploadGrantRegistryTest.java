package com.playdata.calen.travel.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.playdata.calen.common.config.MinioProperties;
import com.playdata.calen.common.exception.ServiceUnavailableException;
import com.playdata.calen.travel.domain.TravelUploadGrant;
import com.playdata.calen.travel.domain.model.TravelMediaData;
import com.playdata.calen.travel.domain.model.TravelRouteUploadData;
import com.playdata.calen.travel.repository.TravelUploadGrantRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class TravelUploadGrantRegistryTest {
    private final TravelUploadGrantRepository grants = mock(TravelUploadGrantRepository.class);
    private final TravelUploadGrantRegistry registry = registry();
    private final String url = "https://store.example/file?X-Amz-Date=20261103T010203Z&X-Amz-Expires=600&X-Amz-Signature=PRIVATE";

    @Test void tracksOriginalThumbnailsAndRouteWithSignedExpiryWithoutUrl() {
        var thumb = new TravelMediaData.ThumbnailTarget("preview", "PUT", url, "travel-media/7/9/10/.thumbs/480/photo.png", "image/png", 1);
        var photo = new TravelMediaData.Target("PUT", url, "travel-media/7/9/10/photo.png", "photo.png", "photo.png", "image/png", 1, List.of(thumb));
        registry.recordMedia(7L, 9L, 10L, List.of(photo));
        registry.recordRoutes(7L, 9L, 11L, List.of(new TravelRouteUploadData.Target("PUT", url,
                "travel-media/7/9/routes/11/route.gpx", "route.gpx", "route.gpx", "xml", 1)));
        var captured = ArgumentCaptor.forClass(TravelUploadGrant.class);
        verify(grants, times(3)).save(captured.capture());
        var rows = captured.getAllValues();
        assertThat(rows).extracting(TravelUploadGrant::getKind).containsExactly(TravelUploadGrant.Kind.MEDIA,
                TravelUploadGrant.Kind.THUMBNAIL, TravelUploadGrant.Kind.ROUTE_GPX);
        assertThat(rows.get(1).getOriginalKey()).isEqualTo(photo.objectKey());
        assertThat(rows.get(1).getStorageBucket()).isEqualTo("travel-bucket");
        assertThat(rows.get(1).getExpiresAtUtc()).isEqualTo(LocalDateTime.of(2026, 11, 3, 1, 12, 3));
        assertThat(rows.get(1).toString()).doesNotContain("PRIVATE");
    }

    @Test void rejectsForeignOwnerRoutesAndMismatchedThumbnail() {
        var route = new TravelRouteUploadData.Target("PUT", url, "travel-media/8/9/routes/11/file.gpx", "s", "f", "xml", 1);
        assertThatThrownBy(() -> registry.recordRoutes(7L, 9L, 11L, List.of(route))).isInstanceOf(ServiceUnavailableException.class);
        assertThat(TravelUploadGrantPaths.isThumbnail(7L, 9L, 10L, "travel-media/7/9/10/photo.jpg",
                "travel-media/7/9/10/.thumbs/480/someone-else.jpg")).isFalse();
        assertThat(TravelUploadGrantPaths.isMedia(7L, 9L, 10L, "old/../travel-media/7/9/10/photo.jpg")).isFalse();
        assertThat(TravelUploadGrantPaths.isMedia(7L, 9L, 10L, "old/prefix/7/9/10/photo.jpg")).isTrue();
        verifyNoInteractions(grants);
    }

    private TravelUploadGrantRegistry registry() {
        var properties = new MinioProperties(); properties.setBucket_cloud("travel-bucket"); properties.setPresignedUrlExpirySeconds(600);
        var result = new TravelUploadGrantRegistry(grants, properties);
        ReflectionTestUtils.setField(result, "configuredPrefix", "travel-media");
        return result;
    }
}
